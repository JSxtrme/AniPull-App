package com.js.anipull

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
// Rate limit 600 req / 60 s. 2.0-dev2.9: pacing is 100 ms per
// endpoint (apiGet) — the old shared 400 ms serialised the
// parallel legs; 100 ms is still far inside the limit.
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

    // 2.0-dev2.9: paced PER ENDPOINT at 100 ms, not the shared
    // 400 ms — the legs below run in parallel, and the old shared
    // lastCallMs made each async leg wait ~400 ms behind the
    // previous one (pacing alone was a ~2 s floor on a resolve).
    // The API allows 600 req / 60 s (10/s); a resolve makes ~7
    // calls, so 100 ms per endpoint is far inside the limit and
    // a burst of parallel legs is still far under it.
    private fun apiPaceKey(path: String): String {
        val parts = path.split("/").filter { it.isNotBlank() }
        // /watch/{id}/episode/{n}/<leg> -> one key per leg (gigi /
        // revo / calm / momo / servers / download), so parallel
        // legs never wait on each other. Everything else keys on
        // its first segment (anime / search).
        if (parts.size >= 2 && parts[0] == "watch") return "watch:" + parts.last().substringBefore("?")
        return parts.firstOrNull()?.substringBefore("?") ?: "api"
    }

    private suspend fun apiGet(path: String, timeoutMs: Int = 12_000): HttpResponse {
        paceFor(apiPaceKey(path), 100L)
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

    // 2.0-dev2.8: parse the API's stated "filesize" label
    // ("27MB", "1.2GB", "404MB") into bytes. Same digits-first
    // style as the resolution parsing above; a malformed label
    // parses to null, never a crash. This is the API's stated size
    // for THIS file only — it is exact (sizeIsEstimate = false),
    // but only as a FALLBACK when the probe returns no total, and
    // it is never borrowed for another server's file.
    private fun parseApiFilesizeBytes(raw: String?): Long? {
        val label = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = Regex("""^(\d+(?:\.\d+)?)\s*([KMGT]?B?)$""", RegexOption.IGNORE_CASE)
            .find(label) ?: return null
        val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2)?.uppercase().orEmpty()
        val multiplier = when (unit) {
            "", "B" -> 1L
            "K", "KB" -> 1024L
            "M", "MB" -> 1024L * 1024L
            "G", "GB" -> 1024L * 1024L * 1024L
            "T", "TB" -> 1024L * 1024L * 1024L * 1024L
            else -> return null
        }
        return runCatching { (value * multiplier).toLong() }.getOrNull()?.takeIf { it > 0 }
    }

    // 2.0-dev2.6: `quality` is null when the API states none
    // ("auto", blank, or missing): UNKNOWN, not 1080. The old
    // code ran the raw string through qualityHeight(), whose
    // no-digits fallback is 1080 — so an unstated leg was silently
    // promoted to 1080p and a 360p DDL could win on a lie.
    private data class StreamSource(val url: String, val quality: Int?, val isM3u8: Boolean)
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
                // Digits only — "auto" / blank / missing is null
                // (unknown), never the old silent 1080 default.
                val q = s.optString("quality").takeIf { it.isNotBlank() }
                    ?.let { raw -> Regex("""(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() }
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

    // Null quality sorts below every known height: prefer the
    // highest known height at/below wanted, else the lowest known
    // height, else an unstated source (the caller / race probes it
    // or HlsDownloader re-parses the master — it is never trusted
    // as 1080 on the label alone).
    private fun pickSource(sources: List<StreamSource>, quality: String, wantM3u8: Boolean): StreamSource? {
        val pool = sources.filter { it.isM3u8 == wantM3u8 }.ifEmpty { sources }
        if (pool.isEmpty()) return null
        val wanted = qualityHeight(quality)
        return pool.filter { (it.quality ?: 0) in 1..wanted }.maxByOrNull { it.quality ?: 0 }
            ?: pool.filter { it.quality != null }.minByOrNull { it.quality ?: 0 }
            ?: pool.firstOrNull()
    }

    // 2.0-dev2.9: one leg's failure must not kill its sibling legs
    // (coroutineScope cancels ALL children when one child throws)
    // and must never swallow cancellation (the race's 15 s cap
    // cancels this resolve). Each leg runs through here: network
    // failures become a null leg, CancellationException propagates.
    private suspend fun <T> isolateLeg(block: suspend () -> T?): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // 2.0-dev2.9: the gigi DDL leg, extracted verbatim from
    // resolveEpisode so it can run in parallel with the HLS legs.
    // Returns the probed DDL link, or null when the leg has nothing
    // (no download entry / non-200). The probe BEFORE the floor
    // decision is unchanged: a probed height overrides the stated
    // one, a failed probe keeps the stated quality (or unknown).
    private suspend fun fetchGigiDdl(
        id: Long,
        number: Int,
        wantDub: Boolean,
        wanted: Int,
        floor: Int,
        quality: String,
    ): ResolvedLink? {
        val dlResp = apiGet("/watch/$id/episode/$number/download/gigi")
        if (dlResp.code != 200) return null
        val downloads = runCatching { JSONObject(dlResp.text).optJSONArray("downloads") }.getOrNull()
            ?: return null
        // (url, stated height or null when the API states none,
        // API-stated filesize bytes or null). Unstated entries are
        // kept and the probe decides them, so a good DDL is never
        // hidden by a missing label.
        val options = mutableListOf<Triple<String, Int?, Long?>>()
        for (i in 0 until downloads.length()) {
            val d = downloads.optJSONObject(i) ?: continue
            if (d.optBoolean("isDub") != wantDub) continue
            val url = d.optString("download").takeIf { it.isNotBlank() } ?: continue
            val res = d.optInt("resolution", 0).takeIf { it > 0 }
                ?: Regex("""(\d+)""").find(d.optString("resolution"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(d.optString("quality"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            options += Triple(url, res, parseApiFilesizeBytes(d.optString("filesize")))
        }
        // Highest stated height at/below wanted; else the lowest
        // stated height; else an unstated URL — the probe, not the
        // listing, decides that one.
        val pick = options.filter { (it.second ?: 0) in 1..wanted }.maxByOrNull { it.second ?: 0 }
            ?: options.filter { it.second != null }.minByOrNull { it.second ?: 0 }
            ?: options.firstOrNull()
            ?: return null
        val (bestUrl, statedRes, apiFilesizeBytes) = pick
        val probe = DdlQualityProbe.probeDdlQuality(
            bestUrl,
            mapOf("Referer" to ORIGIN),
            timeoutMs = 4_000,
        )
        val realHeight = probe?.height ?: statedRes
        val ddl = ResolvedLink.DirectMp4(
            bestUrl, ORIGIN,
            actualQuality = realHeight,
            estimatedSizeBytes = probe?.totalBytes ?: apiFilesizeBytes,
            sizeIsEstimate = false,
        )
        if (realHeight == null || realHeight < floor) {
            DownloadLogs.log(
                "RESOLVE",
                "JustAnime gigi: DDL probed ${probe?.height?.let { "${it}p" } ?: "unknown"} (stated ${statedRes?.let { "${it}p" } ?: "none"}) for $quality — below floor or unprovable, kept as a candidate",
            )
        }
        return ddl
    }

    // 2.0-dev2.9: one HLS leg (revo / calm / momo), extracted
    // verbatim from the old sequential loop. A leg whose stream
    // response carries no `headers` is SKIPPED, never fetched
    // headerless: the segment CDNs 403 every headerless segment
    // fetch. Null quality is NOT a floor pass — the returned link
    // is a candidate and the selection below (then the race) decides.
    private suspend fun fetchHlsLeg(
        id: Long,
        number: Int,
        server: String,
        wantDub: Boolean,
        audioKey: String,
        quality: String,
    ): ResolvedLink? {
        val resp = apiGet("/watch/$id/episode/$number/$server")
        if (resp.code != 200) return null
        val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null
        val streamObj = root.optJSONObject(audioKey)
            ?: root.optJSONObject(if (wantDub) "sub" else "dub")
            ?: return null
        val stream = parseServerStream(streamObj)
        if (stream.referer == null && stream.origin == null) return null
        val picked = pickSource(stream.sources, quality, wantM3u8 = true) ?: return null
        DownloadLogs.log(
            "RESOLVE",
            "JustAnime $server: HLS, offered ${picked.quality?.let { "${it}p" } ?: "?"}, Referer=${stream.referer ?: "none"} Origin=${stream.origin ?: "none"}",
        )
        return ResolvedLink.Hls(
            picked.url,
            subtitles = stream.subtitles,
            referer = stream.referer,
            origin = stream.origin,
            actualQuality = picked.quality?.takeIf { it > 0 },
        )
    }

    // 2.0-dev2.9: gigi's direct MP4s (animegg.org times out from
    // the VM, but a residential IP may reach them) — the old last
    // resort, extracted verbatim. Unstated quality (null) is
    // unknown, not 1080: the race probes it before it can win.
    private suspend fun fetchGigiDirect(
        id: Long,
        number: Int,
        wantDub: Boolean,
        audioKey: String,
        quality: String,
    ): ResolvedLink? {
        val resp = apiGet("/watch/$id/episode/$number/gigi")
        if (resp.code != 200) return null
        val root = runCatching { JSONObject(resp.text) }.getOrNull()
        val streamObj = root?.optJSONObject(audioKey)
        val stream = parseServerStream(streamObj)
        val picked = pickSource(stream.sources, quality, wantM3u8 = false) ?: return null
        return ResolvedLink.DirectMp4(picked.url, "https://www.animegg.org/", actualQuality = picked.quality?.takeIf { it > 0 })
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

            // 2.0-dev2.6: quality-aware legs, PROBED. A sub-floor
            // DDL must NOT win just by returning first — a 360p
            // DDL used to beat a 1080p HLS leg. So collect
            // candidates instead of returning: a DDL whose PROBED
            // height meets the floor returns immediately (DDL-first
            // is kept when the DDL is genuinely the best leg);
            // otherwise HLS legs are tried for one that meets it;
            // only when nothing meets the floor is the best
            // (highest) sub-floor candidate returned, tagged with
            // its actual quality so the race can still prefer a
            // better server and the file is named honestly. The
            // probe is what makes this airtight: the /download
            // endpoint sometimes states NO resolution (or a wrong
            // one) for a file that is really 360p, so the stated
            // number is never trusted on its own — the mp4 header
            // is read first (Range request, no full download).
            // 2.0-dev2.9: the legs below run IN PARALLEL
            // (coroutineScope + async) instead of sequentially —
            // the old order (gigi DDL + 4 s probe, then revo, calm,
            // momo, then gigi-direct, each paced 400 ms) cost
            // 5.1–8.8 s per resolve on the phone. Selection keeps
            // the old priority exactly: DDL meeting the floor >
            // HLS leg meeting the floor (revo > calm > momo) >
            // gigi-direct meeting the floor > best sub-floor
            // candidate (highest offered). A leg that throws
            // returns null through isolateLeg and never kills its
            // siblings; the race still compares this server's one
            // returned link against the other servers.
            val wanted = qualityHeight(quality)
            val floor = qualityFloorFor(wanted)

            // All legs race each other: the servers call above is
            // the only sequential step (the legs depend on it).
            // Per-endpoint 100 ms pacing (apiGet) means the
            // parallel legs don't serialise on the pace delay.
            val legs = coroutineScope {
                val jobs = mutableListOf<Deferred<Pair<String, ResolvedLink?>>>()
                if (serverHas("gigi")) {
                    jobs += async { "gigi-ddl" to isolateLeg { fetchGigiDdl(id, number, wantDub, wanted, floor, quality) } }
                }
                for (server in listOf("revo", "calm", "momo")) {
                    if (!serverHas(server)) continue
                    jobs += async { server to isolateLeg { fetchHlsLeg(id, number, server, wantDub, audioKey, quality) } }
                }
                if (serverHas("gigi")) {
                    jobs += async { "gigi-direct" to isolateLeg { fetchGigiDirect(id, number, wantDub, audioKey, quality) } }
                }
                jobs.awaitAll().toMap()
            }
            val linkFor = { leg: String -> legs[leg] }
            // 1. DDL meeting the floor wins (DDL-first, as before).
            linkFor("gigi-ddl")?.takeIf { (it.offeredHeight() ?: 0) >= floor }?.let { return it }
            // 2. First HLS leg meeting the floor, revo > calm > momo.
            for (server in listOf("revo", "calm", "momo")) {
                linkFor(server)?.takeIf { (it.offeredHeight() ?: 0) >= floor }?.let { return it }
            }
            // 3. gigi-direct meeting the floor.
            linkFor("gigi-direct")?.takeIf { (it.offeredHeight() ?: 0) >= floor }?.let { return it }
            // 4. Nothing met the floor: the best sub-floor candidate
            // (highest offered quality; first among ties, in leg
            // priority order) so the race can compare it against
            // other servers instead of failing outright — the race
            // accepts it only as a downgrade.
            listOfNotNull(
                linkFor("gigi-ddl"),
                linkFor("revo"),
                linkFor("calm"),
                linkFor("momo"),
                linkFor("gigi-direct"),
            ).maxByOrNull { it.offeredHeight() ?: 0 }
                ?.let { return it }
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
