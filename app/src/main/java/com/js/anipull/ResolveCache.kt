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

    private fun cacheKey(titleId: Long, number: Int, audio: String, quality: String): String =
        "$titleId|$number|${audioCode(audio)}|$quality"

    @Synchronized
    fun lookup(
        context: Context,
        titleId: Long,
        number: Int,
        audio: String,
        quality: String,
        preferred: Int, // 0 = Auto; 1..9 = explicit first server
        listServer: Int?, // server that served the list, tried first
    ): ResolvedDownload? {
        loadLocked(context)
        val entry = mem[cacheKey(titleId, number, audio, quality)] ?: return null
        val ageMs = System.currentTimeMillis() - entry.resolvedAtMs
        if (ageMs < 0 || ageMs > ttlMs(entry.serverNumber)) {
            mem.remove(cacheKey(titleId, number, audio, quality))
            persistLocked(context)
            return null
        }
        // The winner must match the requested first server: an
        // explicit preferred server, or the list server when the row
        // came from a server list. Auto accepts any winner.
        val first = listServer ?: preferred.takeIf { it in 1..9 }
        if (first != null && entry.serverNumber != first) return null
        DownloadLogs.log("RESOLVE", "cache HIT: Server ${entry.serverNumber}, age ${ageMs}ms")
        return ResolvedDownload(entry.link, entry.serverNumber)
    }

    @Synchronized
    fun store(
        context: Context,
        titleId: Long,
        number: Int,
        audio: String,
        quality: String,
        resolved: ResolvedDownload,
    ) {
        val link = resolved.link
        val server = resolved.serverNumber ?: return
        if (link is ResolvedLink.Failed) return
        loadLocked(context)
        val key = cacheKey(titleId, number, audio, quality)
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
                    val server = e.optInt("server", 0).takeIf { it in 1..9 } ?: return@forEach
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
        when (e.optString("kind")) {
            "mp4" -> ResolvedLink.DirectMp4(
                url = e.getString("url"),
                referer = e.optString("referer", ""),
                subtitles = parseSubs(e.optJSONArray("subs")),
                actualQuality = aq,
            )
            "hls" -> ResolvedLink.Hls(
                masterPlaylistUrl = e.getString("master"),
                subtitles = parseSubs(e.optJSONArray("subs")),
                keyOverrideB64 = e.optString("key", null).takeIf { !it.isNullOrEmpty() },
                ivOverrideB64 = e.optString("iv", null).takeIf { !it.isNullOrEmpty() },
                referer = e.optString("referer", null).takeIf { !it.isNullOrEmpty() },
                origin = e.optString("origin", null).takeIf { !it.isNullOrEmpty() },
                actualQuality = aq,
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
