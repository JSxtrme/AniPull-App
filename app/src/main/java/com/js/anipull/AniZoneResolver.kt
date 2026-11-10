package com.js.anipull

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// 2.0-dev1.6 ENGINE — Server 1.
// Streaming site, self-built (Laravel + Livewire + Alpine, Vidstack
// player). Verified live 2026-11-10: homepage, search, detail,
// episode page, master + variant playlists all 200 to plain requests,
// no challenge, no login, no key. Direct HLS master URL embedded in
// the episode page; 1080p; 9 audio tracks in the manifest (multi-audio
// is picked by HlsDownloader from #EXT-X-MEDIA). AES-128 with rotating
// keys — the existing per-URI key cache in HlsDownloader handles
// this (a new key URI is fetched when it changes). No subtitle tracks
// in the manifest: treat as hard-subbed.
// Domain pinned to anizone.to exactly; the operator's domain churn is
// the standing risk. Episode session encoding:
//   episodeSession = "anizone|<slug>|<number>", animeSession = slug.

internal class AniZoneEpisodeSource : BaseEpisodeSource(
    serverNumber = 1,
    serverDescription = ServerRegistry.description(1),
    sourceName = "AniZone",
) {
    companion object {
        private const val BASE = "https://anizone.to"
    }

    private fun titlesOf(obj: JSONObject): List<String> {
        val out = mutableListOf<String>()
        obj.optString("main_title").takeIf { it.isNotBlank() }?.let { out += it }
        when (val tl = obj.opt("title_list")) {
            is JSONObject -> {
                val keys = tl.keys()
                while (keys.hasNext()) {
                    tl.optString(keys.next()).takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
            is JSONArray -> {
                for (i in 0 until tl.length()) {
                    tl.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
            is String -> if (tl.isNotBlank()) out += tl
        }
        return out
    }

    // Walk parsed JSON looking for arrays of objects that carry the
    // given string field; used for both the search results
    // ("main_title") and the episode list (numeric "slug").
    private fun findObjectArrays(node: Any?, field: String, out: MutableList<JSONArray>) {
        when (node) {
            is JSONArray -> {
                if (node.length() > 0 && node.opt(0) is JSONObject &&
                    (node.optJSONObject(0)?.has(field) == true)
                ) {
                    out += node
                }
                for (i in 0 until node.length()) findObjectArrays(node.opt(i), field, out)
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) findObjectArrays(node.opt(keys.next()), field, out)
            }
        }
    }

    private data class SearchHit(val slug: String, val titles: List<String>, val episodeCount: Int)

    private suspend fun search(title: ApiTitle): List<SearchHit> {
        val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
        pace()
        val resp = httpGet("$BASE/anime?search=$q", timeoutMs = 12_000, followRedirects = true)
        if (resp.code != 200) return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (blob in extractJsonParseStrings(resp.text)) {
            val parsed = runCatching {
                if (blob.trimStart().startsWith("[")) JSONArray(blob) else JSONObject(blob)
            }.getOrNull() ?: continue
            val arrays = mutableListOf<JSONArray>()
            findObjectArrays(parsed, "main_title", arrays)
            for (arr in arrays) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
                    val titles = titlesOf(obj)
                    if (titles.isEmpty()) continue
                    hits += SearchHit(slug, titles, obj.optInt("episode_count", 0))
                }
            }
            if (hits.isNotEmpty()) break
        }
        return hits
    }

    private fun pickHit(hits: List<SearchHit>, title: ApiTitle): SearchHit? {
        if (hits.isEmpty()) return null
        val want = normalize(title.title)
        var best: SearchHit? = null
        var bestScore = Int.MAX_VALUE
        for (hit in hits) {
            val score = hit.titles.minOf { score(it.let(::normalize), want) }
            val total = score * 10
            if (total < bestScore) {
                bestScore = total
                best = hit
            }
        }
        return if (bestScore >= 30) null else best
    }

    private suspend fun fetchSlug(title: ApiTitle): String? {
        val hits = search(title)
        return pickHit(hits, title)?.slug
    }

    private fun episodeTitleOf(obj: JSONObject): String? {
        val tl = obj.opt("title_list")
        if (tl is JSONObject) {
            // Prefer an English title when the map carries one.
            for (key in listOf("en", "English", "english")) {
                tl.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            }
            val keys = tl.keys()
            while (keys.hasNext()) {
                tl.optString(keys.next()).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        if (tl is String && tl.isNotBlank()) return tl
        return obj.optString("title").takeIf { it.isNotBlank() }
    }

    private suspend fun loadEpisodesForSlug(slug: String): EpisodeResult {
        return try {
            pace()
            val resp = httpGet("$BASE/anime/$slug", timeoutMs = 12_000, followRedirects = true)
            if (resp.code != 200) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            val episodes = mutableListOf<EpisodeInfo>()
            for (blob in extractJsonParseStrings(resp.text)) {
                val parsed = runCatching {
                    if (blob.trimStart().startsWith("[")) JSONArray(blob) else JSONObject(blob)
                }.getOrNull() ?: continue
                val arrays = mutableListOf<JSONArray>()
                findObjectArrays(parsed, "slug", arrays)
                for (arr in arrays) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val num = obj.optString("slug").toIntOrNull() ?: continue
                        val type = obj.optString("type")
                        if (type.isNotBlank() && !type.equals("Regular Episode", ignoreCase = true)) continue
                        episodes += EpisodeInfo(
                            number = num,
                            title = episodeTitleOf(obj),
                            duration = obj.optString("duration").takeIf { it.isNotBlank() },
                            audio = "jpn",
                            fansub = null,
                            episodeSession = "anizone|$slug|$num",
                            animeSession = slug,
                        )
                    }
                }
                if (episodes.isNotEmpty()) break
            }
            if (episodes.isEmpty()) {
                return EpisodeResult.Failed("Couldn't read the episode list — tap Retry.")
            }
            episodes.sortBy { it.number }
            // De-duplicate: the same list can appear twice in-page.
            EpisodeResult.Loaded(episodes.distinctBy { it.number to it.audio })
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    override suspend fun loadEpisodes(title: ApiTitle): EpisodeResult {
        return try {
            val slug = fetchSlug(title)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            loadEpisodesForSlug(slug)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    private fun extractMasterUrl(html: String): String? {
        // vidstackPlayer(JSON.parse('{"src":"https://.../master.m3u8",...}'))
        for (blob in extractJsonParseStrings(html)) {
            val m = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""").find(blob)
            if (m != null) return m.value
        }
        // Fallback: any .m3u8 URL in the raw page.
        return Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""").find(html)?.value
    }

    private suspend fun resolveBySlug(slug: String, number: Int): ResolvedLink {
        return try {
            pace()
            val resp = httpGet("$BASE/anime/$slug/$number", timeoutMs = 12_000, followRedirects = true)
            if (resp.code != 200) {
                return ResolvedLink.Failed("Couldn't open the episode page — tap Retry.")
            }
            val master = extractMasterUrl(resp.text)
                ?: return ResolvedLink.Failed("No stream found for this episode on this server.")
            ResolvedLink.Hls(master)
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
            val slug = fetchSlug(title)
                ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            resolveBySlug(slug, number)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 3 && parts[0] == "anizone") {
            return resolveBySlug(parts[1], parts[2].toIntOrNull() ?: episode.number)
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
