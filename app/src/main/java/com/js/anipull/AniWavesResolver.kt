package com.js.anipull

import org.json.JSONObject
import java.net.URLEncoder

// 2.0-dev1.6 ENGINE — Server 6.
// 9anime-family streaming site, full chain verified live 2026-11-10
// (ANIZONE-ANIWAVES-PROBE.md §2): search -> episode list -> server
// list -> sources -> Vidplay getSources -> master.m3u8. All plain
// GETs, no login, no key, no challenge observed from the VM (despite
// the Turnstile script on the homepage). Only 720p was observed for
// the probed server/episode — 1080p is unproven, so the description
// says 720p honestly. No subtitle tracks observed (thumbnails sprite
// only) — treat as hard-subbed; caption tracks are picked up if the
// embed ever serves them.
// Base: aniwaves.tv (aniwaves.ru 301s there).
// Episode session encoding:
//   episodeSession = "aniwaves|<animeId>|<number>",
//   animeSession = animeId string.

internal class AniWavesEpisodeSource : BaseEpisodeSource(
    serverNumber = 6,
    serverDescription = ServerRegistry.description(6),
    sourceName = "AniWaves",
) {
    companion object {
        private const val BASE = "https://aniwaves.tv"
    }

    private data class SearchHit(val id: String, val title: String)

    private suspend fun search(title: ApiTitle): List<SearchHit> {
        val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
        pace()
        val resp = httpGet("$BASE/ajax/anime/search?keyword=$q", timeoutMs = 12_000, followRedirects = true)
        if (resp.code != 200) return emptyList()
        val hits = mutableListOf<SearchHit>()
        // JSON results carrying /watch/<slug>-<id> links.
        val linkRe = Regex("""["']?/watch/([a-z0-9\-]+)-(\d+)["']?""", RegexOption.IGNORE_CASE)
        val titleRe = Regex(""""(?:title|name)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val text = resp.text
        val titles = titleRe.findAll(text).map { unescapeJsString(it.groupValues[1]) }.toList()
        val links = linkRe.findAll(text).toList()
        for ((idx, m) in links.withIndex()) {
            val id = m.groupValues[2]
            val slug = m.groupValues[1]
            val name = titles.getOrNull(idx)
                ?: slug.replace("-", " ")
            hits += SearchHit(id, name)
        }
        // Fallback: the response may be HTML with result cards.
        if (hits.isEmpty()) {
            val cardRe = Regex("""/watch/[a-z0-9\-]+-(\d+)""", RegexOption.IGNORE_CASE)
            for (m in cardRe.findAll(text)) {
                hits += SearchHit(m.groupValues[1], title.title)
            }
        }
        return hits.distinctBy { it.id }
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

    private suspend fun fetchAnimeId(title: ApiTitle): String? {
        return pickHit(search(title), title)?.id
    }

    override suspend fun loadEpisodes(title: ApiTitle, anilistId: Long?): EpisodeResult {
        return try {
            val id = fetchAnimeId(title)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            pace()
            val resp = httpGet("$BASE/ajax/episode/list/$id", timeoutMs = 12_000, followRedirects = true)
            if (resp.code != 200) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            val html = resp.text
            val episodes = mutableListOf<EpisodeInfo>()
            // Per episode: link /watch/<id>/ep-<n>, data-duration
            // (seconds), data-sub / data-dub flags, Japanese title.
            val epRe = Regex(
                """/watch/\d+/ep-(\d+)[^>]*?(?:data-duration="(\d+)")?[^>]*?(?:data-sub="(\d)")?[^>]*?(?:data-dub="(\d)")?""",
                RegexOption.IGNORE_CASE,
            )
            // The attributes may precede the link instead; fall back
            // to a plain link scan with unknown flags.
            val found = epRe.findAll(html).toList()
            if (found.isEmpty()) {
                val linkOnly = Regex("""/watch/\d+/ep-(\d+)""").findAll(html)
                for (m in linkOnly) {
                    val num = m.groupValues[1].toIntOrNull() ?: continue
                    episodes += EpisodeInfo(
                        number = num, title = null, duration = null, audio = "jpn",
                        fansub = null, episodeSession = "aniwaves|$id|$num", animeSession = id,
                    )
                }
            } else {
                for (m in found) {
                    val num = m.groupValues[1].toIntOrNull() ?: continue
                    val durSec = m.groupValues[2].toLongOrNull()
                    val hasSub = m.groupValues[3] != "0"
                    val hasDub = m.groupValues[4] == "1"
                    val audios = buildList {
                        if (hasSub) add("jpn")
                        if (hasDub) add("eng")
                        if (isEmpty()) add("jpn")
                    }
                    for (aud in audios) {
                        episodes += EpisodeInfo(
                            number = num,
                            title = null,
                            duration = durSec?.let { "${it / 60}m" },
                            audio = aud,
                            fansub = null,
                            episodeSession = "aniwaves|$id|$num",
                            animeSession = id,
                        )
                    }
                }
            }
            if (episodes.isEmpty()) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            episodes.sortBy { it.number }
            EpisodeResult.Loaded(episodes.distinctBy { it.number to it.audio })
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    // Server list -> sources -> embed -> getSources -> master.m3u8.
    // Servers tried in order within the requested audio's section:
    // Vidplay first (verified), then BYFMS, DGHG.
    private suspend fun resolveById(id: String, number: Int, audio: String): ResolvedLink {
        return try {
            val wantDub = audioCode(audio) == "eng"
            pace()
            val listResp = httpGet(
                "$BASE/ajax/server/list?servers=$id&eps=$number",
                timeoutMs = 12_000, followRedirects = true,
            )
            if (listResp.code != 200) {
                return ResolvedLink.Failed("Couldn't reach the servers for this episode — tap Retry.")
            }
            val html = listResp.text
            // data-link-id blobs, grouped by section. The SUB section
            // precedes the DUB section in the page; split on the
            // section markers when present.
            val subHtml = html.substringBefore("DUB", html)
            val dubHtml = if (html.contains("DUB")) html.substringAfter("DUB") else ""
            val sectionHtml = if (wantDub && dubHtml.isNotBlank()) dubHtml else subHtml
            val linkIds = Regex("""data-link-id\s*=\s*"([0-9a-fA-F]+)"""")
                .findAll(sectionHtml).map { it.groupValues[1] }.distinct().toList()
            if (linkIds.isEmpty()) {
                // Fall back to every link id on the page.
                val all = Regex("""data-link-id\s*=\s*"([0-9a-fA-F]+)"""")
                    .findAll(html).map { it.groupValues[1] }.distinct().toList()
                if (all.isEmpty()) {
                    return ResolvedLink.Failed("No servers found for this episode on this server.")
                }
                return tryLinkIds(all, number)
            }
            tryLinkIds(linkIds, number)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    private suspend fun tryLinkIds(linkIds: List<String>, number: Int): ResolvedLink {
        for (linkId in linkIds) {
            pace()
            val srcResp = httpGet(
                "$BASE/ajax/sources?id=$linkId&asi=0&autoPlay=0",
                timeoutMs = 12_000, followRedirects = true,
            )
            if (srcResp.code != 200) continue
            val embedUrl = Regex("""https?://[^\s"'<>\\]+/embed-1/[^\s"'<>\\]+""")
                .find(srcResp.text)?.value
                ?: Regex("""["'](https?://[^"']*embed[^"']*)["']""").find(srcResp.text)?.groupValues?.getOrNull(1)
                ?: continue
            val master = resolveEmbed(embedUrl) ?: continue
            return ResolvedLink.Hls(master)
        }
        return ResolvedLink.Failed("No working stream found on this server for this episode.")
    }

    private suspend fun resolveEmbed(embedUrl: String): String? {
        return try {
            pace()
            val pageResp = httpGet(embedUrl, timeoutMs = 12_000, followRedirects = true)
            if (pageResp.code != 200) return null
            // The embed page fetches getSources?id=<token> (relative
            // to /embed-1/). Find the token in the page, else reuse
            // the embed path's own id.
            val token = Regex("""getSources\?id=([A-Za-z0-9\-_]+)""").find(pageResp.text)?.groupValues?.getOrNull(1)
                ?: Regex("""/embed-1/([A-Za-z0-9\-_]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
                ?: return null
            val base = embedUrl.substringBefore("/embed-1/")
            pace()
            val srcResp = httpGet("$base/embed-1/getSources?id=$token", timeoutMs = 12_000, followRedirects = true)
            if (srcResp.code != 200) return null
            val root = runCatching { JSONObject(srcResp.text) }.getOrNull() ?: return null
            val sources = root.optString("sources").takeIf { it.isNotBlank() }
                ?: root.optJSONArray("sources")?.optJSONObject(0)?.optString("file")?.takeIf { it.isNotBlank() }
                ?: root.optString("file").takeIf { it.isNotBlank() }
            if (sources.isNullOrBlank() || !sources.contains(".m3u8")) return null
            sources
        } catch (_: Exception) {
            null
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
            val id = fetchAnimeId(title)
                ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            resolveById(id, number, audio)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 3 && parts[0] == "aniwaves") {
            return resolveById(parts[1], parts[2].toIntOrNull() ?: episode.number, episode.audio)
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
