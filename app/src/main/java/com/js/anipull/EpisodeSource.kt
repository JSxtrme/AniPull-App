package com.js.anipull

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 2.0-dev1.6 ENGINE — episode sources (multi-source).
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
//
// IDENTITY RULE: `sourceName` is internal/logging ONLY and must never
// reach the UI. What the user sees is the server NUMBER (1..9) and the
// generic `serverDescription` — no source names anywhere user-visible.

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

// One soft-subtitle track. `language` is a short code where known
// ("en", "es", "fr" …; locale forms like "en-US" are kept verbatim).
// `url` is a direct .vtt/.ass/.srt file URL. Downloaded as a sidecar
// next to the video — best-effort, never fails the job.
data class SubtitleTrack(
    val language: String,
    val url: String,
    val label: String? = null,
)

sealed class ResolvedLink {
    // Plain mp4. referer MUST be sent on the download GET (Kwik
    // rejects downloads without it). Expires: no — a direct mp4 URL
    // is stable until the file is fetched.
    data class DirectMp4(val url: String, val referer: String, val subtitles: List<SubtitleTrack> = emptyList()) : ResolvedLink()
    // Master playlist URL. m3u8 URLs expire in 3-5 minutes: the
    // caller must hand this to HlsDownloader immediately, in the
    // same job, with no gap — never queue it for later.
    // keyOverrideB64 / ivOverrideB64: base64 of 16 raw bytes. When
    // set, HlsDownloader uses the decoded bytes for ALL segment keys
    // instead of fetching them (AnimeStream's wrapped key). The IV
    // override applies only when the playlist KEY tag has no explicit
    // IV. `referer` is sent on playlist/segment/key fetches when the
    // upstream requires it (e.g. MegaPlay); null = no Referer header.
    data class Hls(
        val masterPlaylistUrl: String,
        val subtitles: List<SubtitleTrack> = emptyList(),
        val keyOverrideB64: String? = null,
        val ivOverrideB64: String? = null,
        val referer: String? = null,
    ) : ResolvedLink()
    data class Failed(val message: String) : ResolvedLink()
}

// A resolved link plus the server whose link won (1..9, null when
// every candidate failed). The number — never a source name — is
// what the UI shows ("Server N") and what the engine stores on the
// job. 2.0-dev1.7.
data class ResolvedDownload(val link: ResolvedLink, val serverNumber: Int?)

interface EpisodeSource {
    // Internal/logging ONLY — must never reach the UI.
    val sourceName: String
    // 1..9, see ServerRegistry. The ONLY user-facing identity.
    val serverNumber: Int
    // Generic description, no source name — see ServerRegistry.
    val serverDescription: String
    suspend fun loadEpisodes(title: ApiTitle): EpisodeResult
    suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink
    // Resolve by (series identity + episode number), the decoupled
    // path used with the media-index episode list. `anilistId` is the
    // AniList id when known (from the media-index match), else null.
    // `audio` is "Sub"/"Dub" (or "jpn"/"eng"). Default: not supported.
    suspend fun resolveEpisode(
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
    ): ResolvedLink {
        return ResolvedLink.Failed("This server can't resolve by episode number.")
    }
}

object EpisodeSources {
    // clearanceCookie: a cf_clearance value the USER's own browser
    // session already holds, passed in as a plain string. Null =
    // no clearance available -> the source reports SourcePending
    // and makes no network call at all.
    // userAgent: the WebView User-Agent that minted the clearance.
    // A clearance is void if replayed with a different UA than the
    // one that minted it, so the minting UA must be replayed with
    // the cookie (research §8.1). Null -> the default browser UA.
    fun pahe(clearanceCookie: String?, userAgent: String? = null): EpisodeSource =
        PaheEpisodeSource(clearanceCookie, userAgent)

    fun aniZone(): EpisodeSource = AniZoneEpisodeSource()
    fun justAnime(): EpisodeSource = JustAnimeEpisodeSource()
    fun aniPm(): EpisodeSource = AniPmEpisodeSource()
    fun nino2(): EpisodeSource = Nino2EpisodeSource()
    fun aniWaves(): EpisodeSource = AniWavesEpisodeSource()
    fun anikoto(): EpisodeSource = AnikotoEpisodeSource()
    fun animeStream(): EpisodeSource = AnimeStreamEpisodeSource()
    fun anv(): EpisodeSource = AnvEpisodeSource()
}

// Try candidates in order; first non-Failed wins. If all fail, the
// last Failed is returned (or a generic message when nothing ran),
// with serverNumber null. onAttempt fires with each server number
// as it is tried, so the UI can show live progress ("Trying
// Server N…"). The Pahe candidate is passed in already constructed
// with its clearance — the engine NEVER solves Cloudflare.
object MultiSourceResolver {
    suspend fun resolveWithFailover(
        candidates: List<EpisodeSource>,
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
        onAttempt: ((Int) -> Unit)? = null,
    ): ResolvedDownload {
        var last: ResolvedLink = ResolvedLink.Failed("All servers failed — tap Retry.")
        for (src in candidates) {
            onAttempt?.invoke(src.serverNumber)
            try {
                val r = src.resolveEpisode(title, anilistId, number, audio, quality)
                if (r !is ResolvedLink.Failed) return ResolvedDownload(r, src.serverNumber)
                last = r
            } catch (e: Exception) {
                last = ResolvedLink.Failed(friendlyDownloadError(e))
            }
        }
        return ResolvedDownload(last, null)
    }

    suspend fun loadEpisodesWithFailover(
        candidates: List<EpisodeSource>,
        title: ApiTitle,
    ): EpisodeResult {
        var last: EpisodeResult = EpisodeResult.Failed("All servers failed — tap Retry.")
        var sawPending = false
        var sawFailed = false
        for (src in candidates) {
            try {
                when (val r = src.loadEpisodes(title)) {
                    is EpisodeResult.Loaded -> return r
                    is EpisodeResult.SourcePending -> sawPending = true
                    is EpisodeResult.Failed -> {
                        last = r
                        sawFailed = true
                    }
                }
            } catch (e: Exception) {
                last = EpisodeResult.Failed(friendlyEpisodeListError(e))
                sawFailed = true
            }
        }
        // Every source pending (e.g. only the clearance-gated source
        // was offered and it has no clearance) reports pending, not
        // failure — the UI then offers the clearance flow.
        if (sawPending && !sawFailed) return EpisodeResult.SourcePending
        return last
    }
}

// --- Shared helpers for the resolvers --------------------------------

// Normalize for title matching: lowercase, strip all non-alphanumerics.
internal fun normalizeTitle(s: String): String =
    s.lowercase().filter { it.isLetterOrDigit() }

// 0 exact, 1 prefix, 2 contains, 3 no match. Lower is better.
internal fun titleMatchScore(found: String, want: String): Int = when {
    found.isEmpty() || want.isEmpty() -> 3
    found == want -> 0
    found.startsWith(want) || want.startsWith(found) -> 1
    found.contains(want) || want.contains(found) -> 2
    else -> 3
}

internal fun audioCode(audio: String): String =
    if (audio.equals("eng", ignoreCase = true) || audio.equals("Dub", ignoreCase = true)) "eng" else "jpn"

internal fun qualityHeight(quality: String): Int =
    Regex("""(\d+)""").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080

// Map a human track label ("English", "Español", "en-US") to a short
// language code for sidecar filenames. Unknown labels pass through
// sanitized by the caller.
internal fun languageCodeFromLabel(label: String?): String {
    val raw = label?.trim().orEmpty()
    if (raw.isEmpty()) return "en"
    // Already a code / locale: en, en-US, pt-BR …
    if (Regex("""^[A-Za-z]{2,3}(-[A-Za-z]{2})?$""").matches(raw)) return raw
    val lower = raw.lowercase()
        .substringBefore("[")
        .substringBefore("(")
        .trim()
    return when {
        lower.startsWith("english") -> "en"
        lower.startsWith("spanish") || lower.startsWith("espa") -> "es"
        lower.startsWith("french") || lower.startsWith("fran") -> "fr"
        lower.startsWith("german") || lower.startsWith("deutsch") -> "de"
        lower.startsWith("italian") -> "it"
        lower.startsWith("portuguese") || lower.startsWith("portugu") -> "pt"
        lower.startsWith("russian") -> "ru"
        lower.startsWith("arabic") -> "ar"
        lower.startsWith("japanese") -> "ja"
        lower.startsWith("hindi") -> "hi"
        lower.startsWith("tamil") -> "ta"
        lower.startsWith("telugu") -> "te"
        lower.startsWith("indonesian") -> "id"
        lower.startsWith("thai") -> "th"
        lower.startsWith("vietnamese") -> "vi"
        lower.startsWith("polish") -> "pl"
        lower.startsWith("malay") -> "ms"
        lower.startsWith("chinese") || lower.startsWith("mandarin") -> "zh"
        lower.startsWith("korean") -> "ko"
        lower.startsWith("turkish") -> "tr"
        lower.startsWith("dutch") -> "nl"
        lower.startsWith("czech") -> "cs"
        lower.startsWith("hungarian") -> "hu"
        lower.startsWith("romanian") -> "ro"
        lower.startsWith("ukrainian") -> "uk"
        lower.startsWith("bengali") -> "bn"
        lower.startsWith("malayalam") -> "ml"
        else -> "en"
    }
}

// Shared base for the HTTP resolvers: 0.4s pacing between calls,
// blocking I/O confined to Dispatchers.IO, common title/audio utils.
// No Cloudflare solving, no WebView, no CookieManager anywhere.
internal abstract class BaseEpisodeSource(
    override val serverNumber: Int,
    override val serverDescription: String,
    override val sourceName: String,
) : EpisodeSource {
    protected var lastCallMs = 0L

    protected suspend fun pace(ms: Long = 400L) {
        val wait = ms - (System.currentTimeMillis() - lastCallMs)
        if (wait > 0) delay(wait)
        lastCallMs = System.currentTimeMillis()
    }

    protected fun normalize(s: String): String = normalizeTitle(s)

    protected fun score(found: String, want: String): Int = titleMatchScore(found, want)

    protected suspend fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
    ): HttpResponse = withContext(Dispatchers.IO) {
        EngineHttp.requestWithRetry(url, headers = headers, timeoutMs = timeoutMs, followRedirects = followRedirects)
    }

    protected suspend fun httpPostJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val all = headers + mapOf("Content-Type" to "application/json")
        EngineHttp.requestWithRetry(
            url, method = "POST", headers = all,
            body = jsonBody.toByteArray(Charsets.UTF_8),
            timeoutMs = timeoutMs, followRedirects = false,
        )
    }

    // Parse a JSON episode number that may arrive as 1.0 (Double).
    protected fun jsonEpisodeNumber(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it > 0 }
        is String -> value.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
        else -> null
    }

    protected fun minutesToDuration(minutes: Long?): String? =
        minutes?.takeIf { it > 0 }?.let { "${it}m" }

    protected fun msToDuration(ms: Long?): String? =
        ms?.takeIf { it > 0 }?.let { "${it / 60_000}m" }
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

// Unescape a JS single-quoted string literal body: \' \\ \n \r \t
// \uXXXX \xXX and any other \c -> c. Used to decode Alpine
// x-data JSON.parse('...') blobs and similar embedded JSON.
internal fun unescapeJsString(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    val hex = s.substring((i + 2).coerceAtMost(s.length), (i + 6).coerceAtMost(s.length))
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: 'u')
                    i += 4
                }
                'x' -> {
                    val hex = s.substring((i + 2).coerceAtMost(s.length), (i + 4).coerceAtMost(s.length))
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: 'x')
                    i += 2
                }
                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

// All JSON.parse('...') string bodies in a page, unescaped.
// Callers try each one as JSON until one has the shape they need.
internal fun extractJsonParseStrings(html: String): List<String> {
    val re = Regex("""JSON\.parse\('((?:[^'\\]|\\.)*)'\)""", RegexOption.DOT_MATCHES_ALL)
    return re.findAll(html).map { unescapeJsString(it.groupValues[1]) }.toList()
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

// 2.0-dev1.4: the episode LIST has its own mapper. The list load
// used to reuse the download one, so a failed list told the user
// "Download failed" — wrong action, wrong words. List failures
// speak about the list. (2.0-dev1.6: scrubbed of source names —
// no source name may appear in any user-facing string.)
internal fun friendlyEpisodeListError(e: Exception): String = when (e) {
    is java.net.SocketTimeoutException -> "Couldn't load the episode list — connection timed out. Tap Retry."
    is java.net.UnknownHostException -> "Couldn't load the episode list — no connection. Tap Retry."
    is org.json.JSONException -> "Couldn't read the episode list — tap Retry."
    is java.io.IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Couldn't load the episode list — tap Retry."
    else -> "Couldn't load the episode list — tap Retry."
}
