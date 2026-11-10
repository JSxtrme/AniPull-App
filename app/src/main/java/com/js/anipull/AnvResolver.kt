package com.js.anipull

import org.json.JSONArray
import org.json.JSONObject

// 2.0-dev1.6 ENGINE — Server 9.
// First-party JSON API at anv.to/api, probed live 2026-11-10
// (ANIZONE-ANIWAVES-PROBE.md §3). Modern streaming app (Next.js);
// catalog data is AniList-shaped. Best soft-sub picture of the
// probed sources: 9 subtitle languages as separate .vtt files, sub
// + dub, 3 sources per variant (built-in failover).
//   POST /api/catalog {"operation":"SearchAnime","variables":{"search":...,"page":1,"perPage":5}}
//        -> id (AniLab-range internal id, NOT the AniList id),
//           slug, episodes count, seasonYear, idMal,
//           streamingEpisodes. The catalog exposes NO AniList id
//           (verified live 2026-11-11: the Frieren item carries
//           only id=1000006351 + idMal=52991).
//   POST /api/episode {"anilistId":<AniList id>,"malId":<idMal>,
//        "episode":<n>,"first":true,"prefer":"sub"|"dub"}
//        -> {"status":"ready","variants":[...]} — each variant has
//           sources with an HLS URL + subtitle tracks.
// STATEFUL: the episode endpoint 403s without a session cookie —
// it only answers after a page load sets the `shiro_watch` cookie.
// The cookie is obtained with a plain GET of the site homepage and
// the Set-Cookie reused (no WebView, no login). On a 403 the cookie
// is re-harvested once and the call retried.
// THE BLOCKER (unverified from the VM): the signed playlist
// (/stream/<token>/index.m3u8?k=<sig>) TIMED OUT from the VM —
// no segment bytes, variant list, or key behaviour verified. A
// residential-IP phone test decides this source; the resolver fails
// honestly if the playlist cannot be read at resolve time.
// Episode session encoding:
//   episodeSession = "anv|<catalogId>|<malId>|<number>",
//   animeSession = catalogId string. The id slot is an opaque
//   catalog reference, NOT a verified AniList id — /api/episode's
//   `anilistId` is proven to accept the TRUE AniList id (154587
//   for Frieren); whether it also accepts the catalog id is
//   UNVERIFIED (the VM gets no shiro_watch cookie from plain
//   GETs, so that test needs a residential IP). resolveEpisode
//   therefore prefers a passed-in true AniList id and only falls
//   back to the catalog id; resolveDownload uses the session id
//   first and the chain fails over from there.

internal class AnvEpisodeSource : BaseEpisodeSource(
    serverNumber = 9,
    serverDescription = ServerRegistry.description(9),
    sourceName = "Anv",
) {
    companion object {
        private const val BASE = "https://anv.to"
    }

    // Session cookie harvested from a plain homepage GET. Lifetime
    // unknown; re-harvested on a 403.
    private var sessionCookie: String? = null

    private suspend fun ensureSessionCookie(): String? {
        sessionCookie?.let { return it }
        return refreshSessionCookie()
    }

    private suspend fun refreshSessionCookie(): String? {
        return try {
            pace()
            val resp = httpGet(BASE, timeoutMs = 12_000, followRedirects = true)
            val cookie = resp.setCookies()
                .map { it.substringBefore(";").trim() }
                .firstOrNull { it.startsWith("shiro_watch=") }
            if (cookie != null) sessionCookie = cookie
            cookie
        } catch (_: Exception) {
            null
        }
    }

    private fun cookieHeaders(): Map<String, String> {
        val cookie = sessionCookie ?: return emptyMap()
        return mapOf("Cookie" to cookie)
    }

    private suspend fun catalogPost(operation: String, variables: JSONObject): JSONObject? {
        val body = JSONObject().apply {
            put("operation", operation)
            put("variables", variables)
        }.toString()
        ensureSessionCookie()
        pace()
        var resp = httpPostJson("$BASE/api/catalog", body, headers = cookieHeaders(), timeoutMs = 12_000)
        if (resp.code == 403) {
            // Session may have died: re-harvest once and retry.
            refreshSessionCookie()
            pace()
            resp = httpPostJson("$BASE/api/catalog", body, headers = cookieHeaders(), timeoutMs = 12_000)
        }
        if (resp.code != 200) return null
        return runCatching { JSONObject(resp.text) }.getOrNull()
    }

    private data class CatalogHit(
        val catalogId: Long,
        val title: String,
        val episodesCount: Int,
        val malId: Long?,
        val episodeTitles: Map<Int, String>,
    )

    private fun parseCatalog(root: JSONObject): List<CatalogHit> {
        val out = mutableListOf<CatalogHit>()
        // Response shape is nested (data / results / items); walk
        // for arrays of objects carrying an "episodes" count + titles.
        val arrays = mutableListOf<JSONArray>()
        fun walk(node: Any?) {
            when (node) {
                is JSONArray -> {
                    if (node.length() > 0 && node.opt(0) is JSONObject &&
                        node.optJSONObject(0)?.has("episodes") == true
                    ) {
                        arrays += node
                    }
                    for (i in 0 until node.length()) walk(node.opt(i))
                }
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
            }
        }
        walk(root)
        for (arr in arrays) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("id", -1L)
                if (id <= 0) continue
                val titles = mutableListOf<String>()
                obj.optString("title").takeIf { it.isNotBlank() }?.let { titles += it }
                val t = obj.optJSONObject("titles")
                if (t != null) {
                    for (key in listOf("english", "romaji", "native")) {
                        t.optString(key).takeIf { it.isNotBlank() }?.let { titles += it }
                    }
                }
                if (titles.isEmpty()) continue
                val epTitles = mutableMapOf<Int, String>()
                val streaming = obj.optJSONArray("streamingEpisodes")
                if (streaming != null) {
                    for (j in 0 until streaming.length()) {
                        val ep = streaming.optJSONObject(j) ?: continue
                        val num = jsonEpisodeNumber(ep.opt("episode"))
                            ?: jsonEpisodeNumber(ep.opt("number"))
                            ?: (j + 1)
                        ep.optString("title").takeIf { it.isNotBlank() }?.let { epTitles[num] = it }
                    }
                }
                out += CatalogHit(
                    catalogId = id,
                    title = titles.first(),
                    episodesCount = obj.optInt("episodes", 0),
                    malId = obj.optLong("idMal", -1L).takeIf { it > 0 },
                    episodeTitles = epTitles,
                )
            }
        }
        return out
    }

    private suspend fun searchCatalog(title: ApiTitle): CatalogHit? {
        val variables = JSONObject().apply {
            put("search", title.title)
            put("page", 1)
            put("perPage", 5)
        }
        val root = catalogPost("SearchAnime", variables) ?: return null
        val hits = parseCatalog(root)
        if (hits.isEmpty()) return null
        val want = normalize(title.title)
        var best: CatalogHit? = null
        var bestScore = Int.MAX_VALUE
        for (hit in hits) {
            val s = score(normalize(hit.title), want)
            val total = s * 10
            if (total < bestScore) {
                bestScore = total
                best = hit
            }
        }
        return if (bestScore >= 30) null else best
    }

    private data class EpisodeVariant(
        val sources: List<VariantSource>,
    )

    private data class VariantSource(
        val playlistUrl: String,
        val subtitles: List<SubtitleTrack>,
    )

    private suspend fun episodePost(
        anilistId: Long,
        malId: Long?,
        number: Int,
        prefer: String,
    ): JSONObject? {
        val body = JSONObject().apply {
            put("anilistId", anilistId)
            if (malId != null) put("malId", malId)
            put("episode", number)
            put("first", true)
            put("prefer", prefer)
        }.toString()
        ensureSessionCookie()
        pace()
        var resp = httpPostJson("$BASE/api/episode", body, headers = cookieHeaders(), timeoutMs = 12_000)
        if (resp.code == 403) {
            refreshSessionCookie()
            pace()
            resp = httpPostJson("$BASE/api/episode", body, headers = cookieHeaders(), timeoutMs = 12_000)
        }
        if (resp.code != 200) return null
        return runCatching { JSONObject(resp.text) }.getOrNull()
    }

    private fun parseVariants(root: JSONObject): Map<String, List<VariantSource>> {
        val out = mutableMapOf<String, List<VariantSource>>()
        val variants = root.optJSONArray("variants") ?: return out
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            val kind = v.optString("type").takeIf { it.isNotBlank() }
                ?: v.optString("variant").takeIf { it.isNotBlank() }
                ?: v.optString("name").takeIf { it.isNotBlank() }
                ?: continue
            val sources = mutableListOf<VariantSource>()
            val srcArr = v.optJSONArray("sources")
            if (srcArr != null) {
                for (j in 0 until srcArr.length()) {
                    val s = srcArr.optJSONObject(j) ?: continue
                    val url = s.optString("url").takeIf { it.isNotBlank() }
                        ?: s.optString("file").takeIf { it.isNotBlank() }
                        ?: s.optString("src").takeIf { it.isNotBlank() }
                        ?: continue
                    val subs = mutableListOf<SubtitleTrack>()
                    val tracks = s.optJSONArray("subtitles") ?: s.optJSONArray("tracks")
                    if (tracks != null) {
                        for (k in 0 until tracks.length()) {
                            val t = tracks.optJSONObject(k) ?: continue
                            val subUrl = t.optString("url").takeIf { it.isNotBlank() }
                                ?: t.optString("file").takeIf { it.isNotBlank() }
                                ?: continue
                            val label = t.optString("label").takeIf { it.isNotBlank() }
                            val lang = t.optString("lang").takeIf { it.isNotBlank() }
                                ?: t.optString("language").takeIf { it.isNotBlank() }
                                ?: t.optString("locale").takeIf { it.isNotBlank() }
                                ?: languageCodeFromLabel(label)
                            subs += SubtitleTrack(language = lang, url = subUrl, label = label)
                        }
                    }
                    sources += VariantSource(url, subs)
                }
            }
            out[kind.lowercase()] = sources
        }
        return out
    }

    // The stream URL may be a signed relative path
    // (/stream/<token>/index.m3u8?k=<sig>); absolutize it.
    private fun absolutize(url: String): String =
        if (url.startsWith("/")) "$BASE$url" else url

    // Resolve one episode through /api/episode. Tries the requested
    // variant's sources in order, then the other variant's sources.
    // Each candidate playlist is verified readable before it is
    // handed back (the VM timed out on the signed playlist — a dead
    // playlist must not become a queued job).
    private suspend fun resolveByIds(
        anilistId: Long,
        malId: Long?,
        number: Int,
        audio: String,
    ): ResolvedLink {
        return try {
            val prefer = if (audioCode(audio) == "eng") "dub" else "sub"
            val root = episodePost(anilistId, malId, number, prefer)
                ?: return ResolvedLink.Failed("Couldn't reach the stream for this episode — tap Retry.")
            if (root.optString("status") != "ready") {
                return ResolvedLink.Failed("This episode isn't available on this server right now.")
            }
            val variants = parseVariants(root)
            if (variants.isEmpty()) {
                return ResolvedLink.Failed("No stream found for this episode on this server.")
            }
            val orderedKinds = listOf(prefer, if (prefer == "sub") "dub" else "sub")
            for (kind in orderedKinds) {
                val sources = variants[kind] ?: continue
                for (src in sources) {
                    val url = absolutize(src.playlistUrl)
                    // Verify the signed playlist reads before queueing.
                    val check = try {
                        pace()
                        httpGet(url, headers = cookieHeaders(), timeoutMs = 15_000, followRedirects = true)
                    } catch (_: Exception) {
                        null
                    }
                    if (check?.code == 200 && check.text.contains("#EXTM3U")) {
                        return ResolvedLink.Hls(url, subtitles = src.subtitles)
                    }
                }
            }
            ResolvedLink.Failed("The video host isn't reachable right now — tap Retry.")
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun loadEpisodes(title: ApiTitle): EpisodeResult {
        return try {
            val hit = searchCatalog(title)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            val count = hit.episodesCount
            if (count <= 0) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            // The catalog id IS the AniList id: it goes into the
            // session. An AniLab id (ApiTitle.id, 100000xxxx) must
            // never land in episodeSession/animeSession.
            val anilistId = hit.catalogId
            val episodes = mutableListOf<EpisodeInfo>()
            for (num in 1..count) {
                // One row per episode (audio "jpn"). The download
                // sheet copies the chosen audio into the EpisodeInfo
                // before resolving, so the list never needs a dub row.
                episodes += EpisodeInfo(
                    number = num,
                    title = hit.episodeTitles[num],
                    duration = null,
                    audio = "jpn",
                    fansub = null,
                    episodeSession = "anv|$anilistId|${hit.malId ?: -1}|$num",
                    animeSession = anilistId.toString(),
                )
            }
            EpisodeResult.Loaded(episodes)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
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
            // The passed-in id wins; else the title's own id, but
            // ONLY when it is a plausible AniList id — an AniLab id
            // (100000xxxx) must never reach /api/episode as anilistId.
            val id = when {
                anilistId != null && anilistId > 0 -> anilistId
                title.id in 1 until 1_000_000 -> title.id
                else -> null
            }
            val hit = searchCatalog(title)
            val malId = hit?.malId
            val useId = id ?: hit?.catalogId
                ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            resolveByIds(useId, malId, number, audio)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 4 && parts[0] == "anv") {
            val anilistId = parts[1].toLongOrNull()
                ?: return ResolvedLink.Failed("This episode can't be resolved on this server.")
            val malId = parts[2].toLongOrNull()?.takeIf { it > 0 }
            return resolveByIds(anilistId, malId, parts[3].toIntOrNull() ?: episode.number, episode.audio)
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
