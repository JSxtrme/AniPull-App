package com.js.anipull

import org.json.JSONArray
import org.json.JSONObject

// 2.0-dev1.6 ENGINE — Server 5.
// One static JSON library file + predictable HLS URLs, torn down
// from MiruDL 2.3.1 (MIRUDL-2.3.1-TEARDOWN.md §7). No per-title API
// at all, no auth, plain GETs. Verified live 2026-11-10 (200).
//   GET https://beta.cno.to/library.json  (cached 10 min, like MiruDL)
//   https://beta.cno.to/hls/<folder>/E<episode, zero-padded to 4>/master.m3u8
// Entries are keyed by AniList id and carry title, native title,
// format, status, cover, year, total episodes, score, genres, a
// `folder` name, the list of available episode numbers, and
// audio[] / subs[] language lists. Audio and subtitle tracks are
// parsed from the master playlist's #EXT-X-MEDIA lines at download
// time (HlsDownloader), not here. Episode titles are not in the
// library (MiruDL cross-calls ani.pm for them — that call is gated,
// so titles stay null here rather than guessed).
// The master URL is verified with a GET (200 + #EXTM3U) before it is
// handed back, so a stale folder never becomes a queued dead link.
// Episode session encoding:
//   episodeSession = "nino2|<folder>|<number>",
//   animeSession = anilistId string.

internal class Nino2EpisodeSource : BaseEpisodeSource(
    serverNumber = 5,
    serverDescription = ServerRegistry.description(5),
    sourceName = "Nino2",
) {
    companion object {
        private const val BASE = "https://beta.cno.to"
        private const val LIBRARY_TTL_MS = 10L * 60 * 1000
    }

    private data class LibraryEntry(
        val anilistId: Long,
        val title: String,
        val nativeTitle: String?,
        val folder: String,
        val episodes: List<Int>,
        val audio: List<String>,
    )

    // In-memory cache only (plus a filesDir copy so a restart within
    // the TTL does not re-download the whole catalog). The parsed
    // library is kept in memory; the file is just the raw bytes.
    private val libLock = Any()
    private var cachedLib: List<LibraryEntry>? = null
    private var cachedLibAtMs = 0L

    // MediaIndex-style file cache needs a Context; the source has
    // none, so the library lives in memory for the process lifetime
    // (10-min TTL) and is re-fetched after that. The file copy is
    // skipped — no Context is available here by design.
    private suspend fun fetchLibrary(): List<LibraryEntry>? {
        synchronized(libLock) {
            val mem = cachedLib
            if (mem != null && System.currentTimeMillis() - cachedLibAtMs < LIBRARY_TTL_MS) return mem
        }
        pace()
        val resp = httpGet("$BASE/library.json", timeoutMs = 15_000, followRedirects = true)
        if (resp.code != 200) return null
        val parsed = runCatching { parseLibrary(resp.text) }.getOrNull() ?: return null
        synchronized(libLock) {
            cachedLib = parsed
            cachedLibAtMs = System.currentTimeMillis()
        }
        return parsed
    }

    private fun parseLibrary(text: String): List<LibraryEntry> {
        val out = mutableListOf<LibraryEntry>()
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseEntry(it) }?.let { out += it }
            }
        } else {
            val root = JSONObject(trimmed)
            // Keyed by AniList id: {"154587": {...}, ...}
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                parseEntry(obj, key.toLongOrNull())?.let { out += it }
            }
        }
        return out
    }

    private fun parseEntry(obj: JSONObject, keyId: Long? = null): LibraryEntry? {
        val id = keyId
            ?: obj.optLong("anilistId", -1L).takeIf { it > 0 }
            ?: obj.optLong("anilist_id", -1L).takeIf { it > 0 }
            ?: obj.optLong("id", -1L).takeIf { it > 0 }
            ?: return null
        val folder = obj.optString("folder").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() }
            ?: obj.optString("native").takeIf { it.isNotBlank() }
            ?: return null
        val episodes = mutableListOf<Int>()
        val epsArr = obj.optJSONArray("episodes")
        if (epsArr != null) {
            for (i in 0 until epsArr.length()) {
                jsonEpisodeNumber(epsArr.opt(i))?.let { episodes += it }
            }
        }
        if (episodes.isEmpty()) {
            val total = obj.optInt("totalEpisodes", obj.optInt("total_episodes", obj.optInt("episodes_count", 0)))
            if (total > 0 && total <= 3000) {
                for (n in 1..total) episodes += n
            }
        }
        val audio = mutableListOf<String>()
        val audioArr = obj.optJSONArray("audio")
        if (audioArr != null) {
            for (i in 0 until audioArr.length()) {
                audioArr.optString(i).takeIf { it.isNotBlank() }?.let { audio += it }
            }
        }
        return LibraryEntry(
            anilistId = id,
            title = title,
            nativeTitle = obj.optString("native").takeIf { it.isNotBlank() },
            folder = folder,
            episodes = episodes.sorted(),
            audio = audio,
        )
    }

    private fun matchEntry(lib: List<LibraryEntry>, title: ApiTitle): LibraryEntry? {
        if (title.id > 0) {
            lib.firstOrNull { it.anilistId == title.id }?.let { return it }
        }
        val want = normalize(title.title)
        if (want.isEmpty()) return null
        var best: LibraryEntry? = null
        var bestScore = Int.MAX_VALUE
        for (entry in lib) {
            val titles = listOfNotNull(entry.title, entry.nativeTitle)
            val score = titles.minOf { score(normalize(it), want) }
            if (score < bestScore) {
                bestScore = score
                best = entry
            }
        }
        return if (bestScore >= 30) null else best
    }

    private fun audioRows(entry: LibraryEntry): List<String> {
        if (entry.audio.isEmpty()) return listOf("jpn")
        val out = mutableListOf<String>()
        for (a in entry.audio) {
            when (a.lowercase()) {
                "ja", "jp", "jpn", "japanese" -> if ("jpn" !in out) out += "jpn"
                "en", "eng", "english" -> if ("eng" !in out) out += "eng"
            }
        }
        return out.ifEmpty { listOf("jpn") }
    }

    private fun toEpisodeInfos(entry: LibraryEntry): List<EpisodeInfo> {
        val audios = audioRows(entry)
        val out = mutableListOf<EpisodeInfo>()
        for (num in entry.episodes) {
            for (aud in audios) {
                out += EpisodeInfo(
                    number = num,
                    title = null,
                    duration = null,
                    audio = aud,
                    fansub = null,
                    episodeSession = "nino2|${entry.folder}|$num",
                    animeSession = entry.anilistId.toString(),
                )
            }
        }
        return out
    }

    override suspend fun loadEpisodes(title: ApiTitle): EpisodeResult {
        return try {
            val lib = fetchLibrary()
                ?: return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            val entry = matchEntry(lib, title)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            val episodes = toEpisodeInfos(entry)
            if (episodes.isEmpty()) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            EpisodeResult.Loaded(episodes)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    private suspend fun resolveByEntry(entry: LibraryEntry, number: Int): ResolvedLink {
        return try {
            if (number !in entry.episodes) {
                return ResolvedLink.Failed("This episode isn't listed on this server.")
            }
            val padded = number.toString().padStart(4, '0')
            val master = "$BASE/hls/${entry.folder}/E$padded/master.m3u8"
            // Verify before handing back: a stale folder must not
            // become a queued dead link.
            pace()
            val resp = httpGet(master, timeoutMs = 12_000, followRedirects = true)
            if (resp.code != 200 || !resp.text.contains("#EXTM3U")) {
                return ResolvedLink.Failed("No stream found for this episode on this server.")
            }
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
            val lib = fetchLibrary()
                ?: return ResolvedLink.Failed("Couldn't reach this server — tap Retry.")
            val entry = if (anilistId != null && anilistId > 0) {
                lib.firstOrNull { it.anilistId == anilistId }
            } else {
                matchEntry(lib, title)
            } ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            resolveByEntry(entry, number)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 3 && parts[0] == "nino2") {
            val folder = parts[1]
            val number = parts[2].toIntOrNull() ?: episode.number
            return try {
                val padded = number.toString().padStart(4, '0')
                val master = "$BASE/hls/$folder/E$padded/master.m3u8"
                pace()
                val resp = httpGet(master, timeoutMs = 12_000, followRedirects = true)
                if (resp.code != 200 || !resp.text.contains("#EXTM3U")) {
                    return ResolvedLink.Failed("No stream found for this episode on this server.")
                }
                ResolvedLink.Hls(master)
            } catch (e: Exception) {
                ResolvedLink.Failed(friendlyDownloadError(e))
            }
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
