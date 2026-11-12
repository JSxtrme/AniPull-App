package com.js.anipull

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 2.0-dev2.3 — RESOLVE CACHE (MiruDL pattern: resolve once, cache on
// the episode). A resolved link is stored per title+episode+audio+
// quality so a repeat tap — or the confirm after a pre-resolved sheet
// — skips the whole server chain. Per-source TTLs, because links die
// on different schedules: Pahe m3u8/segment URLs expire in 3–5 min
// (TTL 90 s, never serve an expired Pahe link); JustAnime's signed
// URLs live ~13 days (TTL 24 h cap); Nino 2's constructed URLs have
// no expiry of their own (TTL 1 h, bounded by library freshness);
// everything else 10 min. Only successes are stored, never failures.
// The cache is a shortcut, never a source of truth: a miss behaves
// exactly like no cache at all, and a hit is only served when the
// winning server matches the requested first server (the preferred
// server, or the server that served the list) — an explicit server
// choice is never overridden by a cached link from another server.
// Stored in SharedPreferences (small JSON blob, cap 50 entries) so
// it survives a process restart; expired entries are pruned on read.
object ResolveCache {
    private const val PREFS = "anipull_resolve_cache"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 50

    // Per-source TTLs (ms). Server numbers: see ServerRegistry.
    fun ttlMs(serverNumber: Int): Long = when (serverNumber) {
        3 -> 90_000L // Pahe: m3u8 expires in 3-5 min
        2 -> 24 * 3600_000L // JustAnime: signed URLs live ~13 days
        5 -> 3600_000L // Nino 2: constructed URL, no inherent expiry
        else -> 600_000L // 10 min default
    }

    private data class Entry(
        val link: ResolvedLink,
        val serverNumber: Int,
        val resolvedAtMs: Long,
    )

    private val mem = LinkedHashMap<String, Entry>()
    private var memLoaded = false

    // 2.0-dev2.8: the mode is part of the key — a win decided
    // under fastest-first must never be served as a smallest-file
    // win (or vice versa); each mode has its own entries and
    // flipping the toggle re-resolves under the new mode. Entries
    // stored before this change (no mode suffix) are orphaned:
    // never matched, pruned by TTL on load and the 50-entry cap.
    // 2.0-dev2.10: the direct-download mode joins the key the
    // same way — "s"/"f" for smaller-file, "d"/"h" for direct
    // (d = prefer direct, h = HLS may win). A win decided under
    // one combination of the two toggles is never served under
    // another; pre-change keys are orphaned, never matched.
    // 2.0-dev2.14: the key is now two letters for the two
    // TRI-STATES — size letter "s" (smaller) / "L" (largest) /
    // "f" (fastest), subs letter "d" (direct/Hard) / "S" (soft) /
    // "h" (both). Old keys stay valid and correctly interpreted
    // (the default combination is still "fh"), so no migration.
    private fun cacheKey(titleId: Long, number: Int, audio: String, quality: String, preferSmaller: Boolean, preferDirect: Boolean = false, preferSoft: Boolean = false, preferLargest: Boolean = false): String =
        "$titleId|$number|${audioCode(audio)}|$quality|${when { preferSmaller -> "s"; preferLargest -> "L"; else -> "f" }}${when { preferDirect -> "d"; preferSoft -> "S"; else -> "h" }}"

    @Synchronized
    fun lookup(
        context: Context,
        titleId: Long,
        number: Int,
        audio: String,
        quality: String,
        preferred: Int, // 0 = Auto; 1..10 = explicit first server
        listServer: Int?, // server that served the list, tried first
        // 2.0-dev2.8: the resolve mode the cached win was decided
        // under — a fastest-first win is never served when the
        // toggle is ON, and vice versa. Defaults to fastest-first.
        preferSmaller: Boolean = false,
        // 2.0-dev2.10: same for the direct-download mode.
        preferDirect: Boolean = false,
        // 2.0-dev2.14: same for soft-subs and largest-file modes.
        preferSoft: Boolean = false,
        preferLargest: Boolean = false,
    ): ResolvedDownload? {
        loadLocked(context)
        val entry = mem[cacheKey(titleId, number, audio, quality, preferSmaller, preferDirect, preferSoft, preferLargest)] ?: return null
        val ageMs = System.currentTimeMillis() - entry.resolvedAtMs
        if (ageMs < 0 || ageMs > ttlMs(entry.serverNumber)) {
            mem.remove(cacheKey(titleId, number, audio, quality, preferSmaller, preferDirect, preferSoft, preferLargest))
            persistLocked(context)
            return null
        }
        // The winner must match the requested first server: an
        // explicit preferred server, or the list server when the row
        // came from a server list. Auto accepts any winner.
        val first = listServer ?: preferred.takeIf { it in 1..10 }
        if (first != null && entry.serverNumber != first) return null
        DownloadLogs.log("RESOLVE", "cache HIT: Server ${entry.serverNumber}, age ${ageMs}ms")
        // 2.0-dev2.7: a cached link bypasses the race, so the race's
        // exact-tier rule has to be re-applied here — a 720p link
        // cached under a 1080p key must come back flagged
        // downgraded (UI toasts the real height), never as a silent
        // 1080p win. Unknown quality stays unflagged: unprovable,
        // not provably low.
        val offered = entry.link.offeredHeight()
        val downgraded = offered != null && offered < qualityHeight(quality)
        return ResolvedDownload(entry.link, entry.serverNumber, downgraded)
    }

    @Synchronized
    fun store(
        context: Context,
        titleId: Long,
        number: Int,
        audio: String,
        quality: String,
        resolved: ResolvedDownload,
        // 2.0-dev2.8: the mode the win was decided under — see
        // cacheKey. Defaults to fastest-first.
        preferSmaller: Boolean = false,
        // 2.0-dev2.10: same for the direct-download mode.
        preferDirect: Boolean = false,
        // 2.0-dev2.14: same for soft-subs and largest-file modes.
        preferSoft: Boolean = false,
        preferLargest: Boolean = false,
    ) {
        val link = resolved.link
        val server = resolved.serverNumber ?: return
        if (link is ResolvedLink.Failed) return
        // 2.0-dev2.6 (probe findings): never cache an unknown-quality
        // DDL link. A cached link is served directly, bypassing the
        // race's probe — a pre-fix DDL entry (no quality tag) would
        // keep winning for up to 24 h (JustAnime TTL) and only the
        // post-download check would catch it, exactly the Death Note
        // loop. Unknown DDLs re-resolve (and re-probe) every time.
        if (link is ResolvedLink.DirectMp4 && link.actualQuality == null) return
        loadLocked(context)
        val key = cacheKey(titleId, number, audio, quality, preferSmaller, preferDirect, preferSoft, preferLargest)
        mem[key] = Entry(link, server, System.currentTimeMillis())
        // Cap: drop the oldest entries first.
        while (mem.size > MAX_ENTRIES) {
            val oldest = mem.entries.minByOrNull { it.value.resolvedAtMs }?.key ?: break
            mem.remove(oldest)
        }
        persistLocked(context)
    }

    private fun loadLocked(context: Context) {
        if (memLoaded) return
        memLoaded = true
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, null) ?: return
            val obj = JSONObject(raw)
            val now = System.currentTimeMillis()
            obj.keys().forEach { key ->
                try {
                    val e = obj.getJSONObject(key)
                    val link = parseLink(e) ?: return@forEach
                    // 2.0-dev2.6 (probe findings): drop cached DDL
                    // links with no quality tag — these were resolved
                    // before dev2.5's quality tagging (no "aq"
                    // field), so serving them would bypass the
                    // quality-aware race for up to 24 h and replay
                    // the Death Note 360p loop. They re-resolve
                    // (and re-probe) fresh. HLS links are kept: the
                    // HLS downloader enforces the variant floor
                    // pre-download, so an untagged HLS link is safe.
                    if (link is ResolvedLink.DirectMp4 && link.actualQuality == null) return@forEach
                    val server = e.optInt("server", 0).takeIf { it in 1..10 } ?: return@forEach
                    val at = e.optLong("at", 0)
                    if (at <= 0 || now - at < 0 || now - at > ttlMs(server)) return@forEach
                    mem[key] = Entry(link, server, at)
                } catch (_: Exception) {
                    // One corrupt entry must not kill the whole cache.
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persistLocked(context: Context) {
        try {
            val obj = JSONObject()
            mem.forEach { (key, entry) ->
                obj.put(key, linkToJson(entry.link).put("server", entry.serverNumber).put("at", entry.resolvedAtMs))
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun linkToJson(link: ResolvedLink): JSONObject = when (link) {
        is ResolvedLink.DirectMp4 -> JSONObject().apply {
            put("kind", "mp4")
            put("url", link.url)
            put("referer", link.referer)
            // 2.0-dev2.5: offered quality survives the cache, so a
            // cached hit is judged by the race exactly like a
            // fresh resolve.
            link.actualQuality?.let { put("aq", it) }
            link.estimatedSizeBytes?.let { put("size", it) }
            if (link.sizeIsEstimate) put("sizeEst", true)
            put("subs", subsToJson(link.subtitles))
        }
        is ResolvedLink.Hls -> JSONObject().apply {
            put("kind", "hls")
            put("master", link.masterPlaylistUrl)
            link.keyOverrideB64?.let { put("key", it) }
            link.ivOverrideB64?.let { put("iv", it) }
            link.referer?.let { put("referer", it) }
            link.origin?.let { put("origin", it) }
            link.actualQuality?.let { put("aq", it) }
            link.estimatedSizeBytes?.let { put("size", it) }
            if (link.sizeIsEstimate) put("sizeEst", true)
            put("subs", subsToJson(link.subtitles))
        }
        is ResolvedLink.Failed -> JSONObject().put("kind", "failed")
    }

    private fun subsToJson(subs: List<SubtitleTrack>): JSONArray =
        JSONArray().apply {
            subs.forEach { s ->
                put(JSONObject().apply {
                    put("language", s.language)
                    put("url", s.url)
                    s.label?.let { put("label", it) }
                })
            }
        }

    private fun parseLink(e: JSONObject): ResolvedLink? = try {
        val aq = e.optInt("aq", 0).takeIf { it > 0 }
        val size = e.optLong("size", 0).takeIf { it > 0 }
        val sizeEst = e.optBoolean("sizeEst", false)
        when (e.optString("kind")) {
            "mp4" -> ResolvedLink.DirectMp4(
                url = e.getString("url"),
                referer = e.optString("referer", ""),
                subtitles = parseSubs(e.optJSONArray("subs")),
                actualQuality = aq,
                estimatedSizeBytes = size,
                sizeIsEstimate = sizeEst,
            )
            "hls" -> ResolvedLink.Hls(
                masterPlaylistUrl = e.getString("master"),
                subtitles = parseSubs(e.optJSONArray("subs")),
                keyOverrideB64 = e.optString("key", null).takeIf { !it.isNullOrEmpty() },
                ivOverrideB64 = e.optString("iv", null).takeIf { !it.isNullOrEmpty() },
                referer = e.optString("referer", null).takeIf { !it.isNullOrEmpty() },
                origin = e.optString("origin", null).takeIf { !it.isNullOrEmpty() },
                actualQuality = aq,
                estimatedSizeBytes = size,
                sizeIsEstimate = sizeEst,
            )
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseSubs(arr: JSONArray?): List<SubtitleTrack> {
        if (arr == null) return emptyList()
        val out = ArrayList<SubtitleTrack>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    SubtitleTrack(
                        language = o.optString("language", "en"),
                        url = o.getString("url"),
                        label = o.optString("label", null).takeIf { !it.isNullOrEmpty() },
                    ),
                )
            } catch (_: Exception) {
            }
        }
        return out
    }
}
