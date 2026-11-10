package com.js.anipull

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// 2.0-dev1.6 ENGINE — Server 4.
// Public JSON API at ani.pm/api/anime + Settlar embed, fully torn
// down from MiruDL 2.3.1 (MIRUDL-2.3.1-TEARDOWN.md §6). No key, no
// login, plain GETs with a desktop-Chrome UA. The only source whose
// quality list reaches 2160p. Subtitles come from the Settlar session
// JSON (not the playlist). Cloudflare-gated from datacenter IPs (VM
// gets 403 / challenge page) — a residential IP is what gets through,
// same fragility class as the first source, milder.
// Flow: search -> details -> _packages (sub/dub flags) ->
// playback-bootstrap (settlarSelection) -> settlar/session
// (embedUrl + t token) -> embed.settlar.io session (master HLS +
// subtitles[]).
// Episode session encoding:
//   episodeSession = "anipm|<anilistId>|<number>",
//   animeSession = anilistId string.

internal class AniPmEpisodeSource : BaseEpisodeSource(
    serverNumber = 4,
    serverDescription = ServerRegistry.description(4),
    sourceName = "AniPm",
) {
    companion object {
        private const val API = "https://ani.pm/api"
        private const val SETTLAR = "https://embed.settlar.io/api/embed/session"
    }

    private val apiHeaders = mapOf("Accept" to "application/json")

    private suspend fun apiGet(path: String, timeoutMs: Int = 12_000): HttpResponse {
        pace()
        return httpGet("$API$path", headers = apiHeaders, timeoutMs = timeoutMs, followRedirects = true)
    }

    private fun itemTitles(item: JSONObject): List<String> {
        val out = mutableListOf<String>()
        item.optString("title").takeIf { it.isNotBlank() }?.let { out += it }
        val t = item.optJSONObject("titles")
        if (t != null) {
            for (key in listOf("english", "romaji", "native")) {
                t.optString(key).takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        item.optString("english").takeIf { it.isNotBlank() }?.let { out += it }
        item.optString("romaji").takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    private fun itemsArray(root: JSONObject): JSONArray? {
        return root.optJSONArray("items")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("results")
    }

    // Search -> (anilistId, raw item id). The raw id may carry the
    // "a" (AniList) / "s" (Settlar) prefix the details endpoint wants.
    private suspend fun search(title: ApiTitle): Pair<Long, String?>? {
        val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
        val resp = apiGet("/anime/search?q=$q")
        if (resp.code != 200) return null
        val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null
        val items = itemsArray(root) ?: return null
        val want = normalize(title.title)
        var bestId: Long? = null
        var bestRaw: String? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val rawId = item.optString("id").takeIf { it.isNotBlank() }
            val numericId = rawId?.trimStart('a', 's')?.toLongOrNull()
                ?: item.optLong("anilistId", -1L).takeIf { it > 0 }
                ?: item.optLong("id", -1L).takeIf { it > 0 }
                ?: continue
            val score = itemTitles(item).minOfOrNull { score(normalize(it), want) } ?: 3
            val total = score * 10
            if (total < bestScore) {
                bestScore = total
                bestId = numericId
                bestRaw = rawId
            }
        }
        return if (bestScore >= 30 || bestId == null) null else bestId to bestRaw
    }

    // Details JSON: try the raw id first, then the plain AniList id,
    // then the "a"-prefixed form.
    private suspend fun fetchDetails(anilistId: Long, rawId: String?): JSONObject? {
        val candidates = listOfNotNull(
            rawId?.let { "/anime/ani/$it?routes=e4" },
            "/anime/ani/$anilistId?routes=e4",
            "/anime/ani/a$anilistId?routes=e4",
            rawId?.let { "/anime/series/$it?routes=e4" },
            "/anime/series/$anilistId?routes=e4",
        ).distinct()
        for (path in candidates) {
            val resp = apiGet(path)
            if (resp.code != 200) continue
            val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: continue
            // Details may be nested under "data"/"anime".
            val detail = root.optJSONObject("data") ?: root.optJSONObject("anime") ?: root
            if (detail.has("episodes") || detail.has("title") || detail.has("titles")) return detail
        }
        return null
    }

    private fun parseEpisodes(detail: JSONObject): List<Pair<Int, String?>> {
        val out = mutableListOf<Pair<Int, String?>>()
        val eps = detail.opt("episodes") ?: return out
        if (eps is JSONArray) {
            for (i in 0 until eps.length()) {
                val obj = eps.optJSONObject(i) ?: continue
                val num = jsonEpisodeNumber(obj.opt("number"))
                    ?: jsonEpisodeNumber(obj.opt("episode"))
                    ?: jsonEpisodeNumber(obj.opt("episode_number"))
                    ?: (i + 1)
                val epTitle = obj.optString("title").takeIf { it.isNotBlank() }
                out += num to epTitle
            }
        } else if (eps is JSONObject) {
            val keys = eps.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val num = key.toIntOrNull() ?: continue
                val obj = eps.optJSONObject(key)
                out += num to obj?.optString("title")?.takeIf { it.isNotBlank() }
            }
        }
        return out.sortedBy { it.first }
    }

    // _packages: per-episode sub/dub availability. Shape is only
    // loosely documented, so parse tolerantly: any object carrying
    // hasSub/hasDub plus an episode number contributes a row.
    private suspend fun fetchPackages(anilistId: Long): Map<Int, Pair<Boolean, Boolean>>? {
        val resp = apiGet("/anime/anipm-server/_packages?anilistId=$anilistId")
        if (resp.code != 200) return null
        val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null
        val out = mutableMapOf<Int, Pair<Boolean, Boolean>>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val hasSub = node.has("hasSub")
                    val hasDub = node.has("hasDub")
                    val num = jsonEpisodeNumber(node.opt("episode"))
                        ?: jsonEpisodeNumber(node.opt("number"))
                        ?: jsonEpisodeNumber(node.opt("ep"))
                    if ((hasSub || hasDub) && num != null) {
                        out[num] = (node.optBoolean("hasSub", false) to node.optBoolean("hasDub", false))
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
        }
        walk(root)
        return out.ifEmpty { null }
    }

    private suspend fun resolveId(title: ApiTitle, anilistId: Long?): Pair<Long, String?>? {
        if (anilistId != null && anilistId > 0) return anilistId to null
        if (title.id > 0) return title.id to null
        return search(title)
    }

    override suspend fun loadEpisodes(title: ApiTitle): EpisodeResult {
        return try {
            val (id, rawId) = resolveId(title, null)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            val detail = fetchDetails(id, rawId)
                ?: return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            val eps = parseEpisodes(detail)
            if (eps.isEmpty()) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            val packages = runCatching { fetchPackages(id) }.getOrNull()
            val episodes = mutableListOf<EpisodeInfo>()
            for ((num, epTitle) in eps) {
                val flags = packages?.get(num)
                val audios = when {
                    flags == null -> listOf("jpn", "eng")
                    flags.first && flags.second -> listOf("jpn", "eng")
                    flags.first -> listOf("jpn")
                    flags.second -> listOf("eng")
                    else -> listOf("jpn")
                }
                for (aud in audios) {
                    episodes += EpisodeInfo(
                        number = num,
                        title = epTitle,
                        duration = null,
                        audio = aud,
                        fansub = null,
                        episodeSession = "anipm|$id|$num",
                        animeSession = id.toString(),
                    )
                }
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
            val (id, _) = resolveId(title, anilistId)
                ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            val lang = if (audioCode(audio) == "eng") "dub" else "sub"
            val bootResp = apiGet("/anime/playback-bootstrap/anilist/$id?ep=$number&lang=$lang&core=0")
            if (bootResp.code != 200) {
                return ResolvedLink.Failed("Couldn't reach the stream for this episode — tap Retry.")
            }
            val boot = runCatching { JSONObject(bootResp.text) }.getOrNull()
                ?: return ResolvedLink.Failed("Couldn't read the stream for this episode — tap Retry.")
            val effective = boot.optString("effectiveLanguage").takeIf { it.isNotBlank() }
            if (effective != null && effective != lang) {
                return ResolvedLink.Failed(
                    if (lang == "dub") "This episode has no dub on this server — try Sub."
                    else "This episode has no sub on this server — try Dub.",
                )
            }
            val selection = boot.optString("settlarSelection").takeIf { it.isNotBlank() }
                ?: boot.optString("selection").takeIf { it.isNotBlank() }
                ?: boot.optString("token").takeIf { it.isNotBlank() }
                ?: return ResolvedLink.Failed("Couldn't read the stream for this episode — tap Retry.")
            val sessResp = apiGet(
                "/anime/settlar/session?selection=${URLEncoder.encode(selection, "UTF-8")}" +
                    "&provider=anipm&ep=$number&channel=&telemetry=0",
            )
            if (sessResp.code != 200) {
                return ResolvedLink.Failed("Couldn't reach the stream for this episode — tap Retry.")
            }
            val sess = runCatching { JSONObject(sessResp.text) }.getOrNull()
                ?: return ResolvedLink.Failed("Couldn't read the stream for this episode — tap Retry.")
            val embedUrl = sess.optString("embedUrl").takeIf { it.isNotBlank() }
                ?: sess.optString("embed_url").takeIf { it.isNotBlank() }
                ?: return ResolvedLink.Failed("Couldn't read the stream for this episode — tap Retry.")
            val token = Regex("""[?&]t=([^&]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
                ?: return ResolvedLink.Failed("Couldn't read the stream for this episode — tap Retry.")
            pace()
            val playResp = httpGet(
                "$SETTLAR?t=${URLEncoder.encode(token, "UTF-8")}",
                headers = apiHeaders, timeoutMs = 12_000, followRedirects = true,
            )
            if (playResp.code != 200) {
                return ResolvedLink.Failed("Couldn't reach the stream for this episode — tap Retry.")
            }
            val play = runCatching { JSONObject(playResp.text) }.getOrNull()
                ?: return ResolvedLink.Failed("Couldn't read the stream for this episode — tap Retry.")
            val master = play.optString("source").takeIf { it.isNotBlank() && it.startsWith("https://") }
                ?: return ResolvedLink.Failed("No stream found for this episode on this server.")
            val subs = mutableListOf<SubtitleTrack>()
            val subsArr = play.optJSONArray("subtitles")
            if (subsArr != null) {
                for (i in 0 until subsArr.length()) {
                    val s = subsArr.optJSONObject(i) ?: continue
                    val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val label = s.optString("label").takeIf { it.isNotBlank() }
                    val langCode = s.optString("srclang").takeIf { it.isNotBlank() }
                        ?: languageCodeFromLabel(label)
                    subs += SubtitleTrack(language = langCode, url = url, label = label)
                }
            }
            ResolvedLink.Hls(master, subtitles = subs)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 3 && parts[0] == "anipm") {
            val id = parts[1].toLongOrNull()
                ?: return ResolvedLink.Failed("This episode can't be resolved on this server.")
            val title = ApiTitle(
                id = id, title = "", poster = null, type = null, year = null,
                score = null, overview = null, genres = null, status = null, episodes = null,
            )
            return resolveEpisode(title, id, parts[2].toIntOrNull() ?: episode.number, episode.audio, quality)
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
