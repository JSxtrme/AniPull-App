package com.js.anipull

import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// 2.0-dev1.6 ENGINE — Server 7.
// Anikoto JSON API (anikotoapi.site) + site scraping
// (anikototv.to / anikoto.cz) + MegaPlay embed resolver.
// Verified live 2026-11-10 (ANIKOTO-SOURCE-RESEARCH.md). No auth,
// no key, no login. HLS-only — no DDL path exists on this source.
// The stream URL comes back AES-encrypted in the `enc` field and
// must be decrypted on-device. The key is NEVER embedded here:
// the PRIMARY (and only) path fetches MegaPlay's own public JS
// (https://megaplay.buzz/lib/app.main.js) at runtime and extracts
// the key/IV names from it — if that file stops carrying them,
// resolution fails honestly instead of silently using a stale
// constant. (That is the teardown's lesson: embedded constants
// rotate and break; the JS fetch is the living source.)
// Cipher: AES/CBC/PKCS5Padding; key material zero-padded/truncated
// to a 32-byte buffer, IV to 16 bytes; `enc` is URL-safe Base64
// without padding. Stream Referer for playlist AND segments:
// https://megaplay.buzz/.
// API rate limit 60/window; 0.4 s pacing + the client's caching
// shape (series 10 min) keeps normal use inside it. No API search
// endpoint exists — search scrapes the site (regex on card markup;
// a redesign breaks it silently into empty lists, so an empty
// search is reported as no match, never as an error).
// Episode session encoding:
//   episodeSession = "anikoto|<seriesId>|<embedBase>|<number>",
//   animeSession = seriesId string. <embedBase> is the embed URL
//   without its trailing /sub or /dub.

internal class AnikotoEpisodeSource : BaseEpisodeSource(
    serverNumber = 7,
    serverDescription = ServerRegistry.description(7),
    sourceName = "Anikoto",
) {
    companion object {
        private const val API = "https://anikotoapi.site"
        private const val MEGAPLAY = "https://megaplay.buzz"
        private const val MEGAPLAY_JS = "$MEGAPLAY/lib/app.main.js"
        private const val STREAM_REFERER = "$MEGAPLAY/"
        private val SITE_HOSTS = listOf("anikototv.to", "anikoto.cz")
    }

    private var workingSiteHost: String? = null
    private var megaPlayKeyIv: Pair<ByteArray, ByteArray>? = null

    private suspend fun apiGet(path: String, timeoutMs: Int = 12_000): HttpResponse {
        pace()
        return httpGet(
            "$API$path",
            headers = mapOf("Accept" to "application/json"),
            timeoutMs = timeoutMs, followRedirects = true,
        )
    }

    private suspend fun siteGet(path: String): HttpResponse? {
        val hosts = listOfNotNull(workingSiteHost) + SITE_HOSTS.filter { it != workingSiteHost }
        for (host in hosts) {
            pace()
            try {
                val resp = httpGet("https://$host$path", timeoutMs = 12_000, followRedirects = true)
                if (resp.code == 200 && resp.bytes.isNotEmpty()) {
                    workingSiteHost = host
                    return resp
                }
            } catch (_: Exception) {
                // Next host.
            }
        }
        return null
    }

    private data class SearchHit(val seriesId: String, val title: String)

    // Scrape /filter cards: data-tip="<id>" + d-title link text
    // (img alt as fallback) in the same card block.
    private suspend fun search(title: ApiTitle): List<SearchHit> {
        val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
        val resp = siteGet("/filter?keyword=$q") ?: return emptyList()
        val html = resp.text
        val hits = mutableListOf<SearchHit>()
        val idRe = Regex("""data-tip="(\d+)"""")
        for (m in idRe.findAll(html)) {
            val id = m.groupValues[1]
            val window = html.substring(m.range.first, (m.range.first + 2000).coerceAtMost(html.length))
            val name = Regex("""class="[^"]*d-title[^"]*"[^>]*>([^<]+)<""")
                .find(window)?.groupValues?.getOrNull(1)?.trim()
                ?: Regex("""<img[^>]+alt="([^"]+)"""").find(window)?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            if (name.isBlank()) continue
            hits += SearchHit(id, name)
        }
        return hits.distinctBy { it.seriesId }
    }

    private fun pickHit(hits: List<SearchHit>, title: ApiTitle): SearchHit? {
        if (hits.isEmpty()) return null
        val want = normalize(title.title)
        var best: SearchHit? = null
        var bestScore = Int.MAX_VALUE
        for (hit in hits) {
            val s = score(normalize(hit.title), want)
            if (s < bestScore) {
                bestScore = s
                best = hit
            }
        }
        return if (bestScore >= 30) null else best
    }

    private data class SeriesEpisode(
        val number: Int,
        val title: String?,
        val embedSub: String?,
        val embedDub: String?,
    )

    private data class SeriesData(val anilistId: Long?, val episodes: List<SeriesEpisode>)

    private suspend fun fetchSeries(seriesId: String): SeriesData? {
        val resp = apiGet("/series/$seriesId")
        if (resp.code != 200) return null
        val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null
        if (!root.optBoolean("ok", true)) return null
        val data = root.optJSONObject("data") ?: return null
        val anime = data.optJSONObject("anime")
        val anilistId = anime?.optLong("ani_id", -1L)?.takeIf { it > 0 }
        val episodes = mutableListOf<SeriesEpisode>()
        val arr = data.optJSONArray("episodes") ?: return SeriesData(anilistId, episodes)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val num = jsonEpisodeNumber(obj.opt("number")) ?: continue
            val embed = obj.optJSONObject("embed_url")
            val sub = embed?.optString("sub")?.takeIf { it.isNotBlank() }
            val dub = embed?.optString("dub")?.takeIf { it.isNotBlank() }
            val epTitle = obj.optString("title").takeIf { it.isNotBlank() && it != "Episode $num" }
                ?: obj.optString("jp_title").takeIf { it.isNotBlank() && it != "Episode $num" }
            episodes += SeriesEpisode(num, epTitle, sub, dub)
        }
        return SeriesData(anilistId, episodes.sortedBy { it.number })
    }

    private fun embedBase(embedUrl: String): String =
        embedUrl.removeSuffix("/sub").removeSuffix("/dub")

    private fun toEpisodeInfos(seriesId: String, series: SeriesData): List<EpisodeInfo> {
        val out = mutableListOf<EpisodeInfo>()
        for (ep in series.episodes) {
            // An episode with no embed URL in either audio is
            // unresolvable — no row is emitted for it, honestly.
            val audios = buildList {
                if (ep.embedSub != null) add("jpn" to ep.embedSub)
                if (ep.embedDub != null) add("eng" to ep.embedDub)
            }
            for ((aud, url) in audios) {
                out += EpisodeInfo(
                    number = ep.number,
                    title = ep.title,
                    duration = null,
                    audio = aud,
                    fansub = null,
                    episodeSession = "anikoto|$seriesId|${embedBase(url)}|${ep.number}",
                    animeSession = seriesId,
                )
            }
        }
        return out
    }

    override suspend fun loadEpisodes(title: ApiTitle, anilistId: Long?): EpisodeResult {
        return try {
            val hits = search(title)
            val hit = pickHit(hits, title)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            val series = fetchSeries(hit.seriesId)
                ?: return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            val episodes = toEpisodeInfos(hit.seriesId, series)
            if (episodes.isEmpty()) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            EpisodeResult.Loaded(episodes)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    // --- MegaPlay ------------------------------------------------------

    private fun padOrTruncate(bytes: ByteArray, size: Int): ByteArray {
        if (bytes.size == size) return bytes
        val out = ByteArray(size)
        bytes.copyInto(out, 0, 0, minOf(bytes.size, size))
        return out
    }

    private fun decodeB64UrlSafe(s: String): ByteArray? {
        val cleaned = s.trim().replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching { android.util.Base64.decode(padded, android.util.Base64.DEFAULT) }.getOrNull()
    }

    // Extract the key/IV strings from MegaPlay's own public JS.
    // Only the *names* are known (documented in the teardown); the
    // values come from the live file at runtime. Nothing is embedded.
    private fun extractKeyIvFromJs(js: String): Pair<ByteArray, ByteArray>? {
        fun findValue(name: String): String? {
            val direct = Regex(
                """${Regex.escape(name)}\s*[:=]\s*["']([^"']{8,64})["']""",
            ).find(js)?.groupValues?.getOrNull(1)
            if (direct != null) return direct
            val idx = js.indexOf(name)
            if (idx < 0) return null
            val window = js.substring(idx, (idx + 300).coerceAtMost(js.length))
            return Regex("""["']([^"']{8,64})["']""").find(window)?.groupValues?.getOrNull(1)
        }
        val keyStr = findValue("DOMAINS_ENC_KEY")
            ?: findValue("ENC_KEY")
            ?: return null
        val ivStr = findValue("DOMAINS_ENC_IV")
            ?: findValue("ENC_IV")
            ?: return null
        val keyBytes = padOrTruncate(keyStr.toByteArray(Charsets.UTF_8), 32)
        val ivBytes = padOrTruncate(ivStr.toByteArray(Charsets.UTF_8), 16)
        return keyBytes to ivBytes
    }

    private suspend fun megaPlayKeyIv(): Pair<ByteArray, ByteArray>? {
        megaPlayKeyIv?.let { return it }
        pace()
        val resp = httpGet(MEGAPLAY_JS, timeoutMs = 12_000, followRedirects = true)
        if (resp.code != 200) return null
        val kv = extractKeyIvFromJs(resp.text) ?: return null
        megaPlayKeyIv = kv
        return kv
    }

    private fun decryptEnc(enc: String, keyIv: Pair<ByteArray, ByteArray>): String? {
        val cipherBytes = decodeB64UrlSafe(enc) ?: return null
        val (key, iv) = keyIv
        // Primary: key/IV strings used as raw bytes, padded/truncated
        // (the client's documented transform). Fallback: if the
        // strings were base64, decode them first.
        val attempts = listOf(
            key to iv,
            run {
                val dk = decodeB64UrlSafe(String(key, Charsets.UTF_8).trim())
                val di = decodeB64UrlSafe(String(iv, Charsets.UTF_8).trim())
                if (dk != null && di != null) padOrTruncate(dk, 32) to padOrTruncate(di, 16) else null
            },
        ).mapNotNull { it }
        for ((k, v) in attempts) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(v))
                val plain = cipher.doFinal(cipherBytes)
                val text = String(plain, Charsets.UTF_8)
                if (text.contains("file") || text.contains(".m3u8")) return text
            } catch (_: Exception) {
                // Next attempt.
            }
        }
        return null
    }

    private suspend fun resolveMegaPlay(embedUrl: String, audio: String): ResolvedLink {
        return try {
            val type = if (audioCode(audio) == "eng") "dub" else "sub"
            val site = workingSiteHost ?: SITE_HOSTS.first()
            pace()
            val pageResp = httpGet(
                embedUrl, headers = mapOf("Referer" to "https://$site/"),
                timeoutMs = 12_000, followRedirects = true,
            )
            if (pageResp.code != 200) {
                return ResolvedLink.Failed("Couldn't reach the player for this episode — tap Retry.")
            }
            val dataId = Regex("""data-id="(\d+)"""").find(pageResp.text)?.groupValues?.getOrNull(1)
                ?: return ResolvedLink.Failed("The player layout changed — this episode can't be resolved on this server.")
            pace()
            val srcResp = httpGet(
                "$MEGAPLAY/stream/getSourcesNew?id=$dataId&type=$type",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to MEGAPLAY,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json",
                ),
                timeoutMs = 12_000, followRedirects = true,
            )
            if (srcResp.code != 200) {
                return ResolvedLink.Failed("The player refused this episode — tap Retry.")
            }
            val root = runCatching { JSONObject(srcResp.text) }.getOrNull()
                ?: return ResolvedLink.Failed("Couldn't read the player response — tap Retry.")
            val subs = mutableListOf<SubtitleTrack>()
            val tracks = root.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val kind = t.optString("kind")
                    if (kind.isNotBlank() && kind != "captions" && kind != "") continue
                    val url = t.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val label = t.optString("label").takeIf { it.isNotBlank() }
                    subs += SubtitleTrack(
                        language = languageCodeFromLabel(label),
                        url = url,
                        label = label,
                    )
                }
            }
            val directFile = root.optString("file").takeIf { it.isNotBlank() }
            if (directFile != null) {
                return ResolvedLink.Hls(directFile, subtitles = subs, referer = STREAM_REFERER)
            }
            val enc = root.optString("enc").takeIf { it.isNotBlank() }
                ?: return ResolvedLink.Failed("No stream found for this episode on this server.")
            val keyIv = megaPlayKeyIv()
                ?: return ResolvedLink.Failed("Couldn't read the player's key file — tap Retry.")
            val decrypted = decryptEnc(enc, keyIv)
                ?: return ResolvedLink.Failed("Couldn't decode the stream link — tap Retry.")
            val file = runCatching { JSONObject(decrypted).optString("file") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""").find(decrypted)?.value
                ?: return ResolvedLink.Failed("Couldn't decode the stream link — tap Retry.")
            ResolvedLink.Hls(file, subtitles = subs, referer = STREAM_REFERER)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveEpisode(
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
    ): ResolvedLink {
        return try {
            val hits = search(title)
            if (hits.isEmpty()) {
                return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            }
            // Try the best matches in order; a title search can
            // return same-name shows, and the AniList id (when known)
            // cross-checks the series before resolving.
            val want = normalize(title.title)
            val ordered = hits.sortedBy { score(normalize(it.title), want) }.take(3)
            for (hit in ordered) {
                val series = fetchSeries(hit.seriesId) ?: continue
                if (anilistId != null && anilistId > 0 && series.anilistId != null &&
                    series.anilistId != anilistId
                ) {
                    continue
                }
                val ep = series.episodes.firstOrNull { it.number == number } ?: continue
                val embed = (if (audioCode(audio) == "eng") ep.embedDub ?: ep.embedSub else ep.embedSub ?: ep.embedDub)
                    ?: return ResolvedLink.Failed("No stream found for this episode on this server.")
                return resolveMegaPlay(embed, audio)
            }
            ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 4 && parts[0] == "anikoto") {
            val embedBase = parts[2]
            val audio = episode.audio
            val embedUrl = "$embedBase/${if (audioCode(episode.audio) == "eng") "dub" else "sub"}"
            return resolveMegaPlay(embedUrl, audio)
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
