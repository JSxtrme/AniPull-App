package com.js.anipull

import org.json.JSONObject
import java.net.URLEncoder

// 2.0-dev1.6 ENGINE — Server 2.
// First-party JSON API at core.justanime.to/api, verified live
// 2026-11-10. The ONLY new source with a DDL leg: gigi (AnimeGG
// direct MP4, via JustAnime's own proxy — direct animegg.org URLs
// time out from the VM, so the proxy URL is used as returned). HLS
// legs: revo (FlixCloud, AES-128, soft subs), calm (Krussdomi,
// unencrypted, soft subs), momo (NexaBloom, CF-gated from the VM —
// tried last, residential IP may pass). Ids ARE AniList ids, so the
// media-index list joins directly with no fuzzy title match.
// Hard requirement: `Origin: https://justanime.to` on EVERY core-API
// call, else 403 {"error":"Origin unknown is not allowed"}.
// Rate limit 600 req / 60 s; 0.4 s pacing keeps well under it.
// Signed URLs carry a generous ~13-day expiry, but resolve lazily
// at download start anyway. The revo *download* endpoint 404s from
// the VM (unexplained) — it is NOT used; revo is HLS-only here.
// Episode session encoding:
//   episodeSession = "justanime|<anilistId>|<number>",
//   animeSession = anilistId string.

internal class JustAnimeEpisodeSource : BaseEpisodeSource(
    serverNumber = 2,
    serverDescription = ServerRegistry.description(2),
    sourceName = "JustAnime",
) {
    companion object {
        private const val API = "https://core.justanime.to/api"
        private const val ORIGIN = "https://justanime.to"
    }

    private val apiHeaders = mapOf(
        "Origin" to ORIGIN,
        "Referer" to "$ORIGIN/",
    )

    private suspend fun apiGet(path: String, timeoutMs: Int = 12_000): HttpResponse {
        pace()
        return httpGet("$API$path", headers = apiHeaders, timeoutMs = timeoutMs, followRedirects = true)
    }

    private fun searchTitles(hit: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val t = hit.optJSONObject("title")
        t?.optString("english")?.takeIf { it.isNotBlank() }?.let { out += it }
        t?.optString("romaji")?.takeIf { it.isNotBlank() }?.let { out += it }
        hit.optString("title").takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    // Resolve the AniList id: the passed id first, else the title's
    // own id when it is a plausible AniList id (small int), else a
    // title search. AniLab ids (100000xxxx) are NOT AniList ids and
    // are never verified against the episodes endpoint.
    private suspend fun resolveAnilistId(title: ApiTitle, anilistId: Long?): Long? {
        if (anilistId != null && anilistId > 0) return anilistId
        if (title.id in 1 until 1_000_000) {
            // Verify the id exists before trusting it.
            val check = apiGet("/anime/${title.id}/episodes?page=1")
            if (check.code == 200) return title.id
        }
        val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
        val resp = apiGet("/search?query=$q")
        if (resp.code != 200) return null
        val results = runCatching { JSONObject(resp.text).optJSONArray("results") }.getOrNull()
            ?: return null
        val want = normalize(title.title)
        var bestId: Long? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until results.length()) {
            val hit = results.optJSONObject(i) ?: continue
            val id = hit.optLong("id", -1L)
            if (id <= 0) continue
            val score = searchTitles(hit).minOfOrNull { score(normalize(it), want) } ?: 3
            val tiebreak = (if (hit.optString("type").equals(title.type, ignoreCase = true)) 0 else 1) +
                (if (hit.optInt("year", -1).toString() == title.year) 0 else 1)
            val total = score * 10 + tiebreak
            if (total < bestScore) {
                bestScore = total
                bestId = id
            }
        }
        return if (bestScore >= 30) null else bestId
    }

    private suspend fun fetchAllEpisodes(anilistId: Long): List<EpisodeInfo> {
        val episodes = mutableListOf<EpisodeInfo>()
        var page = 1
        var totalPages = 1
        do {
            val resp = apiGet("/anime/$anilistId/episodes?page=$page")
            if (resp.code != 200) break
            val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: break
            totalPages = root.optInt("totalPages", page)
            val arr = root.optJSONArray("episodes") ?: break
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val num = jsonEpisodeNumber(obj.opt("number")) ?: continue
                val epTitle = obj.optString("title").takeIf { it.isNotBlank() }
                // One row per episode (audio "jpn"): dub availability
                // is verified at resolve time via the servers
                // endpoint; a missing dub fails honestly there
                // instead of hiding the row. The download sheet
                // copies the chosen audio into the EpisodeInfo
                // before resolving, so the list never needs a dub row.
                episodes += EpisodeInfo(
                    number = num,
                    title = epTitle,
                    duration = null,
                    audio = "jpn",
                    fansub = null,
                    episodeSession = "justanime|$anilistId|$num",
                    animeSession = anilistId.toString(),
                )
            }
            page++
        } while (page <= totalPages && page <= 50)
        episodes.sortBy { it.number }
        return episodes
    }

    override suspend fun loadEpisodes(title: ApiTitle, anilistId: Long?): EpisodeResult {
        return try {
            val id = resolveAnilistId(title, anilistId)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            val episodes = fetchAllEpisodes(id)
            if (episodes.isEmpty()) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            EpisodeResult.Loaded(episodes)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    private data class StreamSource(val url: String, val quality: Int, val isM3u8: Boolean)
    // `headers` is the stream response's own `headers` object, taken
    // VERBATIM: the segment CDN enforces the upstream-player origin
    // via Cloudflare and 403s headerless fetches (proven 2026-11-11:
    // revo needs Referer https://flixcloud.cc/, calm needs Referer
    // + Origin https://krussdomi.com/). Values differ per backend,
    // so they are never hardcoded — they come from the API response.
    private data class ServerStream(
        val sources: List<StreamSource>,
        val subtitles: List<SubtitleTrack>,
        val referer: String? = null,
        val origin: String? = null,
    )

    private fun parseServerStream(obj: JSONObject?): ServerStream {
        if (obj == null) return ServerStream(emptyList(), emptyList())
        val sources = mutableListOf<StreamSource>()
        val arr = obj.optJSONArray("sources")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optString("url").takeIf { it.isNotBlank() }
                    ?: s.optString("file").takeIf { it.isNotBlank() }
                    ?: continue
                val q = s.optString("quality").let { qualityHeight(it) }
                val isM3u8 = if (s.has("isM3U8")) s.optBoolean("isM3U8") else url.contains(".m3u8")
                sources += StreamSource(url, q, isM3u8)
            }
        }
        val subs = mutableListOf<SubtitleTrack>()
        val tracks = obj.optJSONArray("tracks") ?: obj.optJSONArray("subtitles")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val url = t.optString("file").takeIf { it.isNotBlank() }
                    ?: t.optString("url").takeIf { it.isNotBlank() }
                    ?: continue
                if (!url.contains(".vtt") && !url.contains(".ass") && !url.contains(".srt")) continue
                val label = t.optString("label").takeIf { it.isNotBlank() }
                val lang = t.optString("lang").takeIf { it.isNotBlank() }
                    ?: t.optString("language").takeIf { it.isNotBlank() }
                    ?: t.optString("srclang").takeIf { it.isNotBlank() }
                    ?: languageCodeFromLabel(label)
                subs += SubtitleTrack(language = lang, url = url, label = label)
            }
        }
        // Per-server media headers, verbatim from the response.
        // Header names are matched case-insensitively; a missing or
        // empty `headers` object leaves both null.
        val headersObj = obj.optJSONObject("headers")
        val referer = headersObj?.let { h ->
            h.keys().asSequence()
                .firstOrNull { it.equals("Referer", ignoreCase = true) }
                ?.let { h.optString(it) }
        }?.takeIf { it.isNotBlank() }
        val origin = headersObj?.let { h ->
            h.keys().asSequence()
                .firstOrNull { it.equals("Origin", ignoreCase = true) }
                ?.let { h.optString(it) }
        }?.takeIf { it.isNotBlank() }
        return ServerStream(sources, subs, referer, origin)
    }

    private fun pickSource(sources: List<StreamSource>, quality: String, wantM3u8: Boolean): StreamSource? {
        val pool = sources.filter { it.isM3u8 == wantM3u8 }.ifEmpty { sources }
        if (pool.isEmpty()) return null
        val wanted = qualityHeight(quality)
        return pool.filter { it.quality <= wanted }.maxByOrNull { it.quality }
            ?: pool.minByOrNull { it.quality }
    }

    override suspend fun resolveEpisode(
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
    ): ResolvedLink {
        return try {
            val id = resolveAnilistId(title, anilistId)
                ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            val wantDub = audioCode(audio) == "eng"
            val audioKey = if (wantDub) "dub" else "sub"

            // Servers availability first: only try servers that
            // actually carry this episode in this audio.
            val serversResp = apiGet("/watch/$id/episode/$number/servers")
            val servers = if (serversResp.code == 200) {
                runCatching { JSONObject(serversResp.text).optJSONObject("servers") }.getOrNull()
            } else {
                null
            }
            fun serverHas(name: String): Boolean {
                val s = servers?.optJSONObject(name) ?: return true // unknown: try it
                return s.optBoolean(audioKey, false)
            }

            // DDL first: gigi download via JustAnime's own proxy.
            // The proxy URL embeds its Referer header; the download
            // itself is a plain GET of that URL.
            if (serverHas("gigi")) {
                val dlResp = apiGet("/watch/$id/episode/$number/download/gigi")
                if (dlResp.code == 200) {
                    val downloads = runCatching { JSONObject(dlResp.text).optJSONArray("downloads") }.getOrNull()
                    if (downloads != null) {
                        val wanted = qualityHeight(quality)
                        var bestUrl: String? = null
                        var bestRes = -1
                        for (i in 0 until downloads.length()) {
                            val d = downloads.optJSONObject(i) ?: continue
                            if (d.optBoolean("isDub") != wantDub) continue
                            val res = d.optInt("resolution", 0)
                            val url = d.optString("download").takeIf { it.isNotBlank() } ?: continue
                            if (res in 1..wanted && res > bestRes) {
                                bestRes = res
                                bestUrl = url
                            }
                        }
                        if (bestUrl == null) {
                            // No quality at/below wanted: take the lowest available.
                            var lowest = Int.MAX_VALUE
                            for (i in 0 until downloads.length()) {
                                val d = downloads.optJSONObject(i) ?: continue
                                if (d.optBoolean("isDub") != wantDub) continue
                                val res = d.optInt("resolution", 0)
                                val url = d.optString("download").takeIf { it.isNotBlank() } ?: continue
                                if (res in 1..lowest) {
                                    lowest = res
                                    bestUrl = url
                                }
                            }
                        }
                        if (bestUrl != null) {
                            return ResolvedLink.DirectMp4(bestUrl, ORIGIN)
                        }
                    }
                }
            }

            // HLS legs, in order: revo (verified playlist + key),
            // calm (verified, unencrypted), momo (VM-gated, last).
            // A leg whose stream response carries no `headers` is
            // SKIPPED, never fetched headerless: the segment CDNs
            // 403 every headerless segment fetch, so trying it only
            // burns the job. If every leg lacks headers, the loop
            // falls through to the honest Failed below.
            for (server in listOf("revo", "calm", "momo")) {
                if (!serverHas(server)) continue
                val resp = apiGet("/watch/$id/episode/$number/$server")
                if (resp.code != 200) continue
                val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: continue
                val streamObj = root.optJSONObject(audioKey)
                    ?: root.optJSONObject(if (wantDub) "sub" else "dub")
                    ?: continue
                val stream = parseServerStream(streamObj)
                if (stream.referer == null && stream.origin == null) continue
                val picked = pickSource(stream.sources, quality, wantM3u8 = true) ?: continue
                DownloadLogs.log(
                    "RESOLVE",
                    "JustAnime $server: HLS, Referer=${stream.referer ?: "none"} Origin=${stream.origin ?: "none"}",
                )
                return ResolvedLink.Hls(
                    picked.url,
                    subtitles = stream.subtitles,
                    referer = stream.referer,
                    origin = stream.origin,
                )
            }

            // Last resort: gigi's direct MP4s (animegg.org times out
            // from the VM, but a residential IP may reach them).
            if (serverHas("gigi")) {
                val resp = apiGet("/watch/$id/episode/$number/gigi")
                if (resp.code == 200) {
                    val root = runCatching { JSONObject(resp.text) }.getOrNull()
                    val streamObj = root?.optJSONObject(audioKey)
                    val stream = parseServerStream(streamObj)
                    val picked = pickSource(stream.sources, quality, wantM3u8 = false)
                    if (picked != null) {
                        return ResolvedLink.DirectMp4(picked.url, "https://www.animegg.org/")
                    }
                }
            }
            ResolvedLink.Failed("No working stream found on this server for this episode.")
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 3 && parts[0] == "justanime") {
            val id = parts[1].toLongOrNull() ?: return ResolvedLink.Failed("This episode can't be resolved on this server.")
            val title = ApiTitle(
                id = id, title = "", poster = null, type = null, year = null,
                score = null, overview = null, genres = null, status = null, episodes = null,
            )
            return resolveEpisode(title, id, parts[2].toIntOrNull() ?: episode.number, episode.audio, quality)
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
