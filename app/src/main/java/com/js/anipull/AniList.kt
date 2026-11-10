package com.js.anipull

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

// 2.0-dev1.7 ENGINE — AniList episode-count fallback.
// Public GraphQL, no key: POST https://graphql.anilist.co.
// Used ONLY when the media-index list is missing: AniList gives an
// episode COUNT (total + aired) and nothing else — NO episode
// titles, NO runtimes, NO stills. The UI builds bare numbered rows
// from the count; nothing here invents any of those.
//
// ID resolution: a known AniList id (passed in, or ApiTitle.id
// when it is a plausible AniList id — small int) is NEVER
// blind-accepted — the returned Media's normalized title must
// match the wanted title. ApiTitle.id may instead be an AniLab id
// (100000xxxx), which lives in a different id space and must NEVER
// be sent to AniList as an id; those go straight to title search.
// Otherwise (or when no id is known) search by title and
// disambiguate by format -> year -> popularity, mirroring
// MediaIndex.matchEntry (same normalization, same tie-break
// order). No confident match -> NotFound, never a guess.
//
// Rate limit: AniList allows ~90 req/min. One call per list load is
// far under it; a trivial in-memory 700 ms pace guards bursts. No
// disk cache.

sealed class AniListCount {
    data class Count(val total: Int?, val aired: Int, val anilistId: Long) : AniListCount()
    object NotFound : AniListCount()
    data class Failed(val message: String) : AniListCount()
}

object AniList {
    private const val GRAPHQL_URL = "https://graphql.anilist.co"
    private const val PACE_MS = 700L

    // Media(id) with everything needed to verify the title and
    // derive the counts. `episodes` is null for ongoing titles;
    // `nextAiringEpisode.episode` is the NEXT episode to air, so
    // aired = episode - 1.
    private const val MEDIA_QUERY = """
        query (${'$'}id: Int) {
          Media(id: ${'$'}id, type: ANIME) {
            id
            title { romaji english native }
            format
            startDate { year }
            popularity
            episodes
            nextAiringEpisode { episode }
          }
        }
    """

    private const val SEARCH_QUERY = """
        query (${'$'}search: String) {
          Page(perPage: 10) {
            media(search: ${'$'}search, type: ANIME) {
              id
              title { romaji english native }
              format
              startDate { year }
              popularity
              episodes
              nextAiringEpisode { episode }
            }
          }
        }
    """

    private data class AniListMedia(
        val id: Long,
        val titles: List<String>,
        val format: String?,
        val year: Int?,
        val popularity: Long,
        val episodes: Int?,
        val nextAiringEpisode: Int?,
    )

    private val paceLock = Any()
    private var lastCallMs = 0L

    private suspend fun pace() {
        val wait = synchronized(paceLock) { PACE_MS - (System.currentTimeMillis() - lastCallMs) }
        if (wait > 0) delay(wait)
        synchronized(paceLock) { lastCallMs = System.currentTimeMillis() }
    }

    private fun postGraphql(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }.toString().toByteArray(Charsets.UTF_8)
        val resp = EngineHttp.requestWithRetry(
            GRAPHQL_URL,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            body = body,
            timeoutMs = 10_000,
            followRedirects = false,
        )
        if (resp.code != 200) {
            throw java.io.IOException("AniList answered HTTP ${resp.code} — tap Retry.")
        }
        return JSONObject(resp.text)
    }

    private fun parseMedia(obj: JSONObject?): AniListMedia? {
        if (obj == null) return null
        val id = obj.optLong("id", -1L)
        if (id <= 0) return null
        val titleObj = obj.optJSONObject("title")
        val titles = listOfNotNull(
            titleObj?.optString("romaji")?.takeIf { it.isNotBlank() },
            titleObj?.optString("english")?.takeIf { it.isNotBlank() },
            titleObj?.optString("native")?.takeIf { it.isNotBlank() },
        )
        return AniListMedia(
            id = id,
            titles = titles,
            format = obj.optString("format").takeIf { it.isNotBlank() },
            year = obj.optJSONObject("startDate")?.let {
                if (it.isNull("year")) null else it.optInt("year").takeIf { y -> y > 0 }
            },
            popularity = obj.optLong("popularity", 0L),
            episodes = if (obj.isNull("episodes")) null else obj.optInt("episodes").takeIf { it > 0 },
            nextAiringEpisode = obj.optJSONObject("nextAiringEpisode")?.let {
                if (it.isNull("episode")) null else it.optInt("episode").takeIf { ep -> ep > 0 }
            },
        )
    }

    private fun fetchById(id: Long): AniListMedia? {
        val json = postGraphql(MEDIA_QUERY, JSONObject().apply { put("id", id.toInt()) })
        return parseMedia(json.optJSONObject("data")?.optJSONObject("Media"))
    }

    private fun searchByTitle(title: String): List<AniListMedia> {
        val json = postGraphql(SEARCH_QUERY, JSONObject().apply { put("search", title) })
        val media = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return emptyList()
        val out = mutableListOf<AniListMedia>()
        for (i in 0 until media.length()) {
            parseMedia(media.optJSONObject(i))?.let { out += it }
        }
        return out
    }

    // The id path: accept ONLY on an exact normalized-title match
    // against any of AniList's title variants (romaji / english /
    // native). A mismatched id is a wrong title, never a guess.
    private fun titlesMatch(media: AniListMedia, want: String): Boolean {
        if (want.isEmpty()) return false
        return media.titles.any { normalizeTitle(it) == want }
    }

    private fun normalizeFormat(format: String?): String? {
        if (format.isNullOrBlank()) return null
        return when (format.trim().uppercase()) {
            "MOVIE" -> "MOVIE"
            "TV" -> "TV"
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "SPECIAL" -> "SPECIAL"
            "TV_SHORT" -> "TV_SHORT"
            "MUSIC" -> "MUSIC"
            else -> format.trim().uppercase()
        }
    }

    // Mirrors MediaIndex.matchEntry: exact normalized match, then
    // format (ApiTitle.type) -> year (ApiTitle.year) -> popularity.
    // No exact match: substring + highest popularity. No match at
    // all: null — never a guess.
    private fun matchSearch(results: List<AniListMedia>, title: ApiTitle): AniListMedia? {
        val want = normalizeTitle(title.title)
        if (want.isEmpty()) return null
        val exact = results.filter { m -> m.titles.any { normalizeTitle(it) == want } }
        if (exact.isEmpty()) {
            val sub = results.filter { m ->
                m.titles.any { t ->
                    val n = normalizeTitle(t)
                    n.contains(want) || want.contains(n)
                }
            }
            return sub.maxByOrNull { it.popularity }
        }
        if (exact.size == 1) return exact[0]
        val wantFormat = normalizeFormat(title.type)
        val byFormat = if (wantFormat != null) {
            exact.filter { normalizeFormat(it.format) == wantFormat }
        } else {
            emptyList()
        }
        val pool = byFormat.ifEmpty { exact }
        if (pool.size == 1) return pool[0]
        val wantYear = title.year?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val byYear = if (wantYear != null) pool.filter { it.year == wantYear } else emptyList()
        val pool2 = byYear.ifEmpty { pool }
        return pool2.maxByOrNull { it.popularity }
    }

    private fun countFrom(media: AniListMedia): AniListCount {
        val total = media.episodes
        // aired = next episode to air minus one when known; else the
        // total when known; else nothing is known — NotFound.
        val aired = when {
            media.nextAiringEpisode != null -> media.nextAiringEpisode - 1
            total != null -> total
            else -> return AniListCount.NotFound
        }
        return AniListCount.Count(total = total, aired = aired.coerceAtLeast(0), anilistId = media.id)
    }

    // One call per list load (two at most: id check, then search).
    // `context` is kept for signature symmetry with MediaIndex; the
    // call needs no storage — no disk cache by design.
    @Suppress("UNUSED_PARAMETER")
    suspend fun episodeCount(context: Context, title: ApiTitle, anilistId: Long? = null): AniListCount {
        return try {
            withContext(Dispatchers.IO) {
                pace()
                // 1) Known AniList id, verified by title — never blind.
                // A passed-in id wins; else the title's own id, but
                // ONLY when it is a plausible AniList id. An AniLab
                // id (100000xxxx) is never sent to AniList as an id.
                val knownId = when {
                    anilistId != null && anilistId > 0 -> anilistId
                    title.id in 1 until 1_000_000 -> title.id
                    else -> null
                }
                if (knownId != null) {
                    val byId = fetchById(knownId)
                    if (byId != null && titlesMatch(byId, normalizeTitle(title.title))) {
                        return@withContext countFrom(byId)
                    }
                    // Unverified id: fall through to search.
                }
                // 2) Search by title, disambiguated like MediaIndex.
                val results = searchByTitle(title.title)
                val match = matchSearch(results, title) ?: return@withContext AniListCount.NotFound
                countFrom(match)
            }
        } catch (e: Exception) {
            AniListCount.Failed(friendlyEpisodeListError(e))
        }
    }
}
