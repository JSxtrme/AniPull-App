package com.js.anipull

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

// 2.0-dev1.6 ENGINE — episode list from sanilili/media-index.
// The list is DECOUPLED from downloads: this only ever returns
// number / title / duration / still / air date. Download resolution
// happens per source by (AniList id + episode number), never by
// inventing sessions. NEVER invent episodes: a missing list is
// reported as NotFound, never guessed.
//
// Upstream (verified live 2026-11-10, no auth, no key):
//   https://cdn.jsdelivr.net/gh/sanilili/media-index@main/data
//   fallback: https://raw.githubusercontent.com/sanilili/media-index/main/data
// index.json ~1.3 MB gzipped on the wire; episodes.json is per-title
// and tiny. stats.json is ~300 bytes and gates the daily refresh.
// Known coverage holes (do not paper over them): movies / OVAs /
// specials mostly have no list, and One Piece (id 21) has none.

data class MediaEpisode(
    val number: Int,
    val title: String?,
    val duration: String?,
    val still: String?,
    val airDate: String?,
)

sealed class MediaIndexResult {
    data class Loaded(val anilistId: Long, val episodes: List<MediaEpisode>) : MediaIndexResult()
    object NotFound : MediaIndexResult()
    data class Failed(val message: String) : MediaIndexResult()
}

object MediaIndex {
    private const val CDN_BASE = "https://cdn.jsdelivr.net/gh/sanilili/media-index@main/data"
    private const val FALLBACK_BASE = "https://raw.githubusercontent.com/sanilili/media-index/main/data"
    private const val INDEX_TTL_MS = 24L * 60 * 60 * 1000 // refresh at most daily
    private const val EPISODES_TTL_MS = 24L * 60 * 60 * 1000
    private const val MISSING_TTL_MS = 7L * 24 * 60 * 60 * 1000 // cache 404s ~7 days

    private data class IndexEntry(
        val id: Long,
        val title: String,
        val format: String?,
        val year: Int?,
        val popularity: Long,
    )

    private val indexLock = Any()
    private var cachedIndex: List<IndexEntry>? = null
    private var cachedIndexAtMs = 0L

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "media-index").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(cacheDir(context), "index.json")
    private fun statsFile(context: Context): File = File(cacheDir(context), "stats.json")
    private fun episodeFile(context: Context, id: Long): File =
        File(File(cacheDir(context), "episodes").apply { mkdirs() }, "$id.json")
    private fun missingFile(context: Context, id: Long): File =
        File(File(cacheDir(context), "episodes").apply { mkdirs() }, "$id.missing")

    // Fetch a URL from the CDN, falling back to the raw GitHub host.
    // Returns null only when neither host answered with a 200.
    private fun fetchFromBases(path: String, acceptGzip: Boolean): ByteArray? {
        val headers = if (acceptGzip) mapOf("Accept-Encoding" to "gzip") else emptyMap()
        for (base in listOf(CDN_BASE, FALLBACK_BASE)) {
            try {
                val resp = EngineHttp.request(
                    "$base/$path", headers = headers, timeoutMs = 15_000, followRedirects = true,
                )
                if (resp.code == 200 && resp.bytes.isNotEmpty()) {
                    return maybeGunzip(resp.bytes)
                }
            } catch (_: Exception) {
                // Next host.
            }
        }
        return null
    }

    private fun maybeGunzip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        return try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (_: Exception) {
            bytes
        }
    }

    private fun parseIndex(text: String): List<IndexEntry> {
        val out = mutableListOf<IndexEntry>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", -1L)
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: continue
            if (id <= 0) continue
            out += IndexEntry(
                id = id,
                title = title,
                format = obj.optString("format").takeIf { it.isNotBlank() },
                year = if (obj.isNull("year")) null else obj.optInt("year"),
                popularity = obj.optLong("popularity", 0L),
            )
        }
        return out
    }

    private fun loadCachedIndex(context: Context): List<IndexEntry>? {
        synchronized(indexLock) {
            val mem = cachedIndex
            if (mem != null && System.currentTimeMillis() - cachedIndexAtMs < INDEX_TTL_MS) return mem
        }
        val file = indexFile(context)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() >= INDEX_TTL_MS) return null
        return try {
            val parsed = parseIndex(file.readText())
            synchronized(indexLock) {
                cachedIndex = parsed
                cachedIndexAtMs = System.currentTimeMillis()
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    // Returns the index, refreshing it when stale. The refresh is
    // gated on stats.json last_updated: if the upstream has not
    // rebuilt since our copy, the cached index is kept and its
    // timestamp touched instead of re-downloading ~1.3 MB.
    private suspend fun getIndex(context: Context): List<IndexEntry>? = withContext(Dispatchers.IO) {
        loadCachedIndex(context)?.let { return@withContext it }
        val statsText = try {
            statsFile(context).takeIf { it.exists() }?.readText()
        } catch (_: Exception) {
            null
        }
        val oldUpdated = statsText?.let { runCatching { JSONObject(it).optString("last_updated") }.getOrNull() }
        val freshStatsBytes = fetchFromBases("stats.json", acceptGzip = false)
        val freshUpdated = freshStatsBytes?.let {
            runCatching { JSONObject(String(it, Charsets.UTF_8)).optString("last_updated") }.getOrNull()
        }
        if (freshStatsBytes != null) {
            try {
                statsFile(context).writeBytes(freshStatsBytes)
            } catch (_: Exception) {
            }
        }
        val indexFileNow = indexFile(context)
        if (!oldUpdated.isNullOrBlank() && oldUpdated == freshUpdated && indexFileNow.exists()) {
            // Upstream unchanged: keep the cached file, refresh its age.
            indexFileNow.setLastModified(System.currentTimeMillis())
            return@withContext try {
                val parsed = parseIndex(indexFileNow.readText())
                synchronized(indexLock) {
                    cachedIndex = parsed
                    cachedIndexAtMs = System.currentTimeMillis()
                }
                parsed
            } catch (_: Exception) {
                null
            }
        }
        val indexBytes = fetchFromBases("index.json", acceptGzip = true) ?: run {
            // Offline with a stale-but-present file: use it rather
            // than failing the list outright.
            if (indexFileNow.exists()) {
                try {
                    return@withContext parseIndex(indexFileNow.readText())
                } catch (_: Exception) {
                }
            }
            return@withContext null
        }
        try {
            indexFileNow.writeBytes(indexBytes)
        } catch (_: Exception) {
        }
        try {
            val parsed = parseIndex(String(indexBytes, Charsets.UTF_8))
            synchronized(indexLock) {
                cachedIndex = parsed
                cachedIndexAtMs = System.currentTimeMillis()
            }
            parsed
        } catch (_: Exception) {
            null
        }
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

    // Title match: normalize (lowercase, strip non-alphanumerics) ->
    // exact match -> disambiguate by format (ApiTitle.type) -> year
    // (ApiTitle.year is already a 4-digit year string from premiered)
    // -> popularity. No exact match: substring + highest popularity.
    // A direct AniList-id hit (ApiTitle.id IS the AniList id) is
    // checked first — same id space as the index, so it can only
    // confirm a match, never invent one.
    private fun matchEntry(index: List<IndexEntry>, title: ApiTitle): IndexEntry? {
        if (title.id > 0) {
            index.firstOrNull { it.id == title.id }?.let { return it }
        }
        val want = normalizeTitle(title.title)
        if (want.isEmpty()) return null
        val exact = index.filter { normalizeTitle(it.title) == want }
        if (exact.isEmpty()) {
            val sub = index.filter {
                val n = normalizeTitle(it.title)
                n.contains(want) || want.contains(n)
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

    private fun parseEpisodes(text: String): List<MediaEpisode> {
        val out = mutableListOf<MediaEpisode>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            // `number` arrives as a float in JSON (1.0) — Double -> Int.
            val number = obj.optDouble("number", -1.0).takeIf { it > 0 }?.toInt() ?: continue
            val runtime = if (obj.isNull("runtime")) null else obj.optLong("runtime", -1L).takeIf { it > 0 }
            out += MediaEpisode(
                number = number,
                title = obj.optString("title").takeIf { it.isNotBlank() },
                duration = runtime?.let { "${it}m" },
                still = obj.optString("still").takeIf { it.isNotBlank() },
                airDate = obj.optString("air_date").takeIf { it.isNotBlank() },
            )
        }
        out.sortBy { it.number }
        return out
    }

    suspend fun loadEpisodes(context: Context, title: ApiTitle): MediaIndexResult {
        return try {
            val index = getIndex(context)
                ?: return MediaIndexResult.Failed("Couldn't load the episode list — check your network and retry.")
            val entry = matchEntry(index, title) ?: return MediaIndexResult.NotFound
            loadEpisodesForId(context, entry.id)
        } catch (e: Exception) {
            MediaIndexResult.Failed(friendlyEpisodeListError(e))
        }
    }

    // Fetch (and cache) one title's episodes.json. Shared by
    // loadEpisodes and by callers that already hold an AniList id.
    suspend fun loadEpisodesForId(context: Context, anilistId: Long): MediaIndexResult {
        return try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val epFile = episodeFile(context, anilistId)
                val missFile = missingFile(context, anilistId)
                if (missFile.exists() && now - missFile.lastModified() < MISSING_TTL_MS) {
                    return@withContext MediaIndexResult.NotFound
                }
                if (epFile.exists() && now - epFile.lastModified() < EPISODES_TTL_MS) {
                    return@withContext try {
                        MediaIndexResult.Loaded(anilistId, parseEpisodes(epFile.readText()))
                    } catch (_: Exception) {
                        MediaIndexResult.Failed("Couldn't read the episode list — tap Retry.")
                    }
                }
                val shard = anilistId / 1000
                var got404 = false
                var body: ByteArray? = null
                for (base in listOf(CDN_BASE, FALLBACK_BASE)) {
                    try {
                        val resp = EngineHttp.request(
                            "$base/anime/$shard/$anilistId/episodes.json",
                            timeoutMs = 15_000, followRedirects = true,
                        )
                        if (resp.code == 200 && resp.bytes.isNotEmpty()) {
                            body = resp.bytes
                            break
                        }
                        if (resp.code == 404) got404 = true
                    } catch (_: Exception) {
                        // Next host.
                    }
                }
                if (body == null) {
                    if (got404) {
                        // Cache the miss ~7 days so a known-missing
                        // list is not re-requested on every open.
                        try {
                            missFile.writeText(now.toString())
                        } catch (_: Exception) {
                        }
                        return@withContext MediaIndexResult.NotFound
                    }
                    return@withContext MediaIndexResult.Failed("Couldn't load the episode list — check your network and retry.")
                }
                try {
                    missFile.delete()
                    epFile.writeBytes(body)
                } catch (_: Exception) {
                }
                MediaIndexResult.Loaded(anilistId, parseEpisodes(String(body, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            MediaIndexResult.Failed(friendlyEpisodeListError(e))
        }
    }
}
