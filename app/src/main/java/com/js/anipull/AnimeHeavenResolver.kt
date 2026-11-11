package com.js.anipull

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 2.0-dev2.11 ENGINE — Server 10.
// AnimeHeaven at animeheaven.me, scraping-only, no public API.
// Verified live from the VM 2026-11-11 (Death Note E1):
//   1080p direct MP4, 137,797,793 bytes, hardsubbed, sub-only,
//   1080p-only. This server must never produce a link for any
//   other audio/quality combo — the fail-fast checks below
//   enforce that before any network call.
// Flow (all regexes tested against real pages):
//   Search: GET /search.php?s=<urlencoded title> ->
//     <a href='anime.php?65134' class='c'>Death Note</a>
//   Series: GET /anime.php?<id> (Referer = search URL) ->
//     gatea("<32-hex token>") per episode, episode number is
//     the next `watch2 bc...>N<` within ~600 chars after it.
//   Gate: GET /gate.php with header `Cookie: key=<token>`
//     (Referer = series URL) -> <source src='...'> URLs.
//     QUIRK (verified twice): gate.php is occasionally slow on
//     first hit (~30 s stall, immediate retry returns in ~1 s),
//     so the gate fetch is a BOUNDED EngineHttp.request call
//     (5 s timeouts, 6 s total deadline) with ONE immediate
//     retry — NOT requestWithRetry's 3-attempt backoff, which
//     is too slow inside the 15 s racer cap.
//   Sources: distinct URLs CONTAINING the episode token, with
//     a trailing `&error` / `&error2` param stripped (player
//     fallback markers — the URL without them serves bytes).
//     Host preference: rx > ct > ck > ax (rx is the download-
//     link host; ct was probe-verified). The download anchor
//     href is `https://rx.animeheaven.me/video.mp4?<token>&d`
//     but the PLAIN source URL (no `&d`) is returned — that is
//     what the probe verified serves bytes.
//   Verify: DdlQualityProbe Range GET per candidate in host
//     order (NEVER HEAD — HEAD hangs on this host, 30 s
//     timeout, twice). First probed candidate wins; if NONE
//     probes, the first candidate is returned with
//     actualQuality=null (unknown tier — the race parks it and
//     the post-download check is the backstop; never fail
//     outright on an unprovable link).
// Episode session encoding:
//   episodeSession = "animeheaven|<seriesId>|<number>",
//   animeSession = seriesId string.

internal class AnimeHeavenEpisodeSource : BaseEpisodeSource(
    serverNumber = 10,
    serverDescription = ServerRegistry.description(10),
    sourceName = "AnimeHeaven",
) {
    companion object {
        private const val BASE = "https://animeheaven.me"
        private const val REFERER = "https://animeheaven.me/"

        // 2.0-dev2.11: SERIES CACHE, copied from AnimeStream's
        // pattern — the sheet pre-warm resolves 8 combos per
        // episode and must not re-fetch search + series 8 times.
        // Series/episode-token data is public catalog data,
        // keyed by normalized title, shared across source
        // instances (the race builds fresh instances per call,
        // so an instance field would never hit), 10 min TTL.
        // Only non-empty token maps are cached; a failed lookup
        // is never cached, so a retry re-fetches. Bounded
        // (8 entries, oldest evicted) and synchronized — the
        // race can resolve the same title on several threads
        // at once.
        private const val SERIES_CACHE_TTL_MS = 10 * 60_000L
        private const val SERIES_CACHE_MAX_ENTRIES = 8
        private data class CachedSeries(
            val seriesId: String,
            val episodeTokens: Map<Int, String>,
            val atMs: Long,
        )
        private val seriesCache = LinkedHashMap<String, CachedSeries>()
        private val seriesCacheLock = Any()

        private fun seriesCacheGet(key: String): CachedSeries? {
            synchronized(seriesCacheLock) {
                val entry = seriesCache[key] ?: return null
                if (System.currentTimeMillis() - entry.atMs > SERIES_CACHE_TTL_MS) {
                    seriesCache.remove(key)
                    return null
                }
                return entry
            }
        }

        private fun seriesCachePut(key: String, value: CachedSeries) {
            synchronized(seriesCacheLock) {
                if (seriesCache.size >= SERIES_CACHE_MAX_ENTRIES) {
                    seriesCache.entries.firstOrNull()?.key?.let { seriesCache.remove(it) }
                }
                seriesCache[key] = value
            }
        }

        // Download-link host preference, best first. rx is the
        // host the download anchor points at; ct was probe-
        // verified (Range GET -> 206 video/mp4). Unknown hosts
        // sort last, never first.
        private val HOST_RANK = listOf(
            "rx.animeheaven.me",
            "ct.animeheaven.me",
            "ck.animeheaven.me",
            "ax.animeheaven.me",
        )
    }

    private data class SeriesHit(val id: String, val title: String)

    private fun searchUrl(title: ApiTitle): String {
        val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
        return "$BASE/search.php?s=$q"
    }

    private suspend fun search(title: ApiTitle): List<SeriesHit> {
        val resp = try {
            paceFor("search", 100L)
            httpGet(searchUrl(title), timeoutMs = 12_000, followRedirects = true)
        } catch (_: Exception) {
            return emptyList()
        }
        if (resp.code != 200) return emptyList()
        // <a href='anime.php?65134' class='c'>Death Note</a>
        val re = Regex("""<a href=['"]anime\.php\?(\d+)['"][^>]*>([^<]+)</a>""")
        return re.findAll(resp.text).mapNotNull { m ->
            val id = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = m.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SeriesHit(id, name)
        }.toList()
    }

    private fun pickSeries(hits: List<SeriesHit>, title: ApiTitle): SeriesHit? {
        if (hits.isEmpty()) return null
        val want = normalize(title.title)
        var best: SeriesHit? = null
        var bestScore = Int.MAX_VALUE
        for (hit in hits) {
            // score() is 0 exact / 1 prefix / 2 contains / 3 no
            // match — x10 like AnimeStream's pickSeries, so a
            // truncated anchor title still matches on a prefix
            // hit and only a score of 3 (30) is rejected.
            val total = score(normalize(hit.title), want) * 10
            if (total < bestScore) {
                bestScore = total
                best = hit
            }
        }
        return if (bestScore >= 30) null else best
    }

    // Episode tokens in document order: gatea("<32-hex>") per
    // episode, the episode number being the next
    // `watch2 bc...>N<` within ~600 chars after the match.
    // Verified: 37 pairs, eps 1-37.
    private fun parseEpisodeTokens(html: String): Map<Int, String> {
        val tokenRe = Regex("""gatea\(\s*"([0-9a-f]{32})"""")
        val numberRe = Regex("""watch2 bc[^>]*>(\d+)<""")
        val out = LinkedHashMap<Int, String>()
        for (m in tokenRe.findAll(html)) {
            val token = m.groupValues.getOrNull(1) ?: continue
            val windowEnd = (m.range.last + 600).coerceAtMost(html.length)
            val window = html.substring(m.range.last, windowEnd)
            val number = numberRe.find(window)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            // First token wins on a duplicate number — the page
            // lists each episode once, so a duplicate is a parse
            // artefact, not a second source.
            if (!out.containsKey(number)) out[number] = token
        }
        return out
    }

    private suspend fun fetchSeriesTokens(seriesId: String, searchReferer: String): Map<Int, String> {
        val resp = try {
            paceFor("series", 100L)
            httpGet(
                "$BASE/anime.php?$seriesId",
                headers = mapOf("Referer" to searchReferer),
                timeoutMs = 12_000,
                followRedirects = true,
            )
        } catch (_: Exception) {
            return emptyMap()
        }
        if (resp.code != 200) return emptyMap()
        return parseEpisodeTokens(resp.text)
    }

    // (seriesId, episode number -> gate token). Null = no series
    // matched the title. A matched series with an empty token map
    // is returned (not cached) so the caller can report the list
    // failure instead of a title mismatch.
    private suspend fun resolveSeries(title: ApiTitle): Pair<String, Map<Int, String>>? {
        val key = normalize(title.title)
        seriesCacheGet(key)?.let { return it.seriesId to it.episodeTokens }
        val hit = pickSeries(search(title), title) ?: return null
        val tokens = fetchSeriesTokens(hit.id, searchUrl(title))
        // Only a non-empty map is cached: a failed/empty lookup
        // is never cached, so a retry re-fetches instead of
        // replaying the failure for 10 minutes.
        if (tokens.isNotEmpty()) {
            seriesCachePut(key, CachedSeries(hit.id, tokens, System.currentTimeMillis()))
        }
        return hit.id to tokens
    }

    override suspend fun loadEpisodes(title: ApiTitle, anilistId: Long?): EpisodeResult {
        return try {
            val (seriesId, tokens) = resolveSeries(title)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            if (tokens.isEmpty()) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            val out = tokens.keys.sorted().map { number ->
                EpisodeInfo(
                    number = number,
                    title = null,
                    duration = null,
                    audio = "jpn",
                    fansub = null,
                    episodeSession = "animeheaven|$seriesId|$number",
                    animeSession = seriesId,
                )
            }
            EpisodeResult.Loaded(out)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    // Gate fetch with ONE immediate retry. EngineHttp.request is
    // called directly (NOT httpGet/requestWithRetry's 3-attempt
    // backoff — too slow inside the 15 s racer cap): each
    // attempt gets a 5 s socket timeout, a 5 s first-byte
    // deadline and a 6 s total deadline, so a stalled first hit
    // (~30 s stall, verified twice) dies fast and the immediate
    // retry answers in ~1 s. Null = both attempts failed.
    private suspend fun fetchGate(token: String, seriesId: String): HttpResponse? {
        paceFor("gate", 100L)
        val headers = mapOf(
            "Cookie" to "key=$token",
            "Referer" to "$BASE/anime.php?$seriesId",
        )
        repeat(2) {
            val resp = runCatching {
                withContext(Dispatchers.IO) {
                    EngineHttp.request(
                        "$BASE/gate.php",
                        headers = headers,
                        timeoutMs = 5_000,
                        followRedirects = true,
                        firstByteDeadlineMs = 5_000,
                        totalDeadlineMs = 6_000,
                    )
                }
            }.getOrNull()
            if (resp != null && resp.code == 200) return resp
            // Immediate retry — no backoff sleep: the verified
            // stall pattern is "first hit hangs, retry is fast".
        }
        return null
    }

    // Distinct <source src='...'> URLs containing the episode
    // token, trailing `&error` / `&error2` stripped (player
    // fallback markers — the URL without them serves bytes),
    // ordered by host preference (rx > ct > ck > ax, stable
    // within one host so document order is preserved).
    private fun parseSourceUrls(gateHtml: String, token: String): List<String> {
        val re = Regex("""<source src='([^']+)'""")
        val urls = re.findAll(gateHtml)
            .map { it.groupValues.getOrNull(1).orEmpty() }
            .filter { it.isNotBlank() && it.contains(token) }
            .map { it.removeSuffix("&error2").removeSuffix("&error") }
            .distinct()
            .toList()
        return urls.sortedBy { url ->
            val host = runCatching { java.net.URL(url).host }.getOrNull()?.lowercase()
            HOST_RANK.indexOf(host).takeIf { it >= 0 } ?: HOST_RANK.size
        }
    }

    // Gate -> source URLs -> probe in host order. First probed
    // candidate wins; if none probes, the first candidate is
    // returned with actualQuality=null (unknown tier — the race
    // parks it and the post-download check is the backstop).
    private suspend fun resolveFromToken(token: String, seriesId: String): ResolvedLink {
        val gate = fetchGate(token, seriesId)
            ?: return ResolvedLink.Failed("Couldn't reach the stream for this episode — tap Retry.")
        val candidates = parseSourceUrls(gate.text, token)
        if (candidates.isEmpty()) {
            return ResolvedLink.Failed("No stream found for this episode on this server.")
        }
        for (url in candidates) {
            // Range GET only, NEVER HEAD (HEAD hangs on this
            // host — 30 s timeout, twice).
            val probe = DdlQualityProbe.probeDdlQuality(url, mapOf("Referer" to REFERER))
                ?: continue
            return ResolvedLink.DirectMp4(
                url,
                REFERER,
                actualQuality = probe.height,
                estimatedSizeBytes = probe.totalBytes,
                sizeIsEstimate = false,
            )
        }
        return ResolvedLink.DirectMp4(candidates.first(), REFERER, actualQuality = null)
    }

    // 1080p-sub-only, enforced BEFORE any network call: this
    // server must never produce a link for any other combo.
    private fun failFast(audio: String, quality: String): ResolvedLink.Failed? {
        if (audioCode(audio) != "jpn") {
            return ResolvedLink.Failed("No dubbed version on this server.")
        }
        if (qualityHeight(quality) != 1080) {
            return ResolvedLink.Failed("This server only offers 1080p.")
        }
        return null
    }

    override suspend fun resolveEpisode(
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
    ): ResolvedLink {
        failFast(audio, quality)?.let { return it }
        return try {
            val (seriesId, tokens) = resolveSeries(title)
                ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            val token = tokens[number]
                ?: return ResolvedLink.Failed("This episode isn't listed on this server.")
            resolveFromToken(token, seriesId)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        failFast(episode.audio, quality)?.let { return it }
        val parts = episode.episodeSession.split("|")
        if (parts.size < 3 || parts[0] != "animeheaven") {
            return ResolvedLink.Failed("This episode can't be resolved on this server.")
        }
        val seriesId = parts[1]
        val number = parts[2].toIntOrNull()
            ?: return ResolvedLink.Failed("This episode can't be resolved on this server.")
        return try {
            // Re-fetch the series page for a FRESH token on every
            // download — tokens are stable, but tokenised URLs
            // get no long-lived caching on the download path, so
            // this deliberately bypasses the series cache.
            val tokens = fetchSeriesTokens(seriesId, "$BASE/anime.php?$seriesId")
            val token = tokens[number]
                ?: return ResolvedLink.Failed("This episode isn't listed on this server.")
            resolveFromToken(token, seriesId)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }
}
