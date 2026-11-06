package com.js.anipull

// 2.0-dev1.0 ENGINE — episode sources.
// A source turns an AniLab title into an episode list and resolves one
// episode (+quality) into a downloadable link. The engine never talks
// to a source directly: DownloadEngine only ever sees ResolvedLink.
//
// HARD RULE (do not "fix" this): a source takes an already-obtained
// clearance cookie as a plain String input and NEVER solves a
// challenge itself — no WebView, no CookieManager harvesting, no
// cf_clearance handling, no Cloudflare bypass of any kind. When the
// cookie is null the Pahe source returns SourcePending without
// making any network call.

data class EpisodeInfo(
    val number: Int,
    val title: String?,
    val duration: String?,
    val audio: String, // "jpn" = sub, "eng" = dub
    val fansub: String?,
    val episodeSession: String,
    val animeSession: String,
)

sealed class EpisodeResult {
    data class Loaded(val episodes: List<EpisodeInfo>, val incomplete: Boolean = false) : EpisodeResult()
    object SourcePending : EpisodeResult()
    data class Failed(val message: String) : EpisodeResult()
}

sealed class ResolvedLink {
    // Plain mp4. referer MUST be sent on the download GET (Kwik
    // rejects downloads without it). Expires: no — a direct mp4 URL
    // is stable until the file is fetched.
    data class DirectMp4(val url: String, val referer: String) : ResolvedLink()
    // Master playlist URL. m3u8 URLs expire in 3-5 minutes: the
    // caller must hand this to HlsDownloader immediately, in the
    // same job, with no gap — never queue it for later.
    data class Hls(val masterPlaylistUrl: String) : ResolvedLink()
    data class Failed(val message: String) : ResolvedLink()
}

interface EpisodeSource {
    val sourceName: String
    suspend fun loadEpisodes(title: ApiTitle): EpisodeResult
    suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink
}

object EpisodeSources {
    // clearanceCookie: a cf_clearance value the USER's own browser
    // session already holds, passed in as a plain string. Null =
    // no clearance available -> the source reports SourcePending
    // and makes no network call at all.
    fun pahe(clearanceCookie: String?): EpisodeSource = PaheEpisodeSource(clearanceCookie)
}

// --- Shared HTTP plumbing for the engine (PaheResolver, HlsDownloader).
// Browser UA + 8-15s timeouts, mirroring the AniLab.kt patterns.
// No new dependency: HttpURLConnection only.

internal const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

internal data class HttpResponse(
    val code: Int,
    val bytes: ByteArray,
    val headers: Map<String, List<String>>,
) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun setCookies(): List<String> = headers.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
        .flatMap { it.value }
}

internal object EngineHttp {
    // One request, no retries. followRedirects=false by default: the
    // Kwik form POST must NOT follow its 302 (the Location IS the mp4).
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
    ): HttpResponse {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", BROWSER_UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Length", body.size.toString())
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(code, bytes, conn.headerFields.filterKeys { it != null })
        } finally {
            conn.disconnect()
        }
    }

    // Retries with backoff; honours Retry-After (seconds) on 429/503.
    // Returns the last response even on failure — the caller decides
    // what a non-200 means (a 403 is a signal, not an exception).
    fun requestWithRetry(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
        maxAttempts: Int = 3,
    ): HttpResponse {
        var last: HttpResponse? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                last = request(url, method, headers, body, timeoutMs, followRedirects)
                if (last.code !in listOf(429, 503) && last.code < 500) return last
                if (attempt == maxAttempts) return last
            } catch (e: Exception) {
                if (attempt == maxAttempts) throw e
                Thread.sleep(1000L shl (attempt - 1))
                continue
            }
            val retryAfter = last?.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60)
            val backoffMs = retryAfter?.times(1000) ?: (1000L shl (attempt - 1))
            Thread.sleep(backoffMs)
        }
        return last ?: throw java.io.IOException("Request failed with no response: $url")
    }
}

// Simple per-second throttle for the speed_mbps setting.
internal class SpeedThrottler(private val limitBytesPerSec: Long) {
    private var windowStartMs = System.currentTimeMillis()
    private var windowBytes = 0L
    suspend fun throttle(bytes: Int) {
        if (limitBytesPerSec <= 0) return
        windowBytes += bytes
        val now = System.currentTimeMillis()
        if (windowBytes >= limitBytesPerSec) {
            val wait = 1000L - (now - windowStartMs)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            windowStartMs = System.currentTimeMillis()
            windowBytes = 0
        } else if (now - windowStartMs >= 1000L) {
            windowStartMs = now
            windowBytes = 0
        }
    }
}

// Resolve a possibly-relative URL against a playlist/page URL.
internal fun resolveUrl(base: String, relative: String): String {
    if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
    return try {
        java.net.URL(java.net.URL(base), relative).toString()
    } catch (_: Exception) {
        relative
    }
}

// Friendly error mapping: raw failures become user-actionable strings
// instead of surfacing HTTP codes (MiruDL lesson).
internal fun friendlyDownloadError(e: Exception): String = when (e) {
    is java.io.FileNotFoundException -> "Video not found on the server — try another quality."
    is java.net.SocketTimeoutException -> "Connection timed out — check your network and retry."
    is java.net.UnknownHostException -> "No connection — check your network and retry."
    is java.io.IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Download failed — tap Retry."
    else -> "Download failed — tap Retry."
}
