package com.js.anipull

import android.content.Context

// 2.0-dev1.6 ENGINE — server registry (multi-source).
// Server numbers/descriptions are the ONLY user-facing identity for
// a download source. No source names anywhere user-visible: the UI
// shows "Server 1" + its description, never a site name.
// Priority order is the failover order: 1 first, 9 last.

data class ServerDef(
    val number: Int,
    val description: String,
)

object ServerRegistry {
    val servers: List<ServerDef> = listOf(
        ServerDef(1, "Fast · 1080p · Multi-audio"),
        ServerDef(2, "Direct downloads · 1080p · Soft subtitles"),
        ServerDef(3, "Direct downloads · 1080p · Hard-subbed"),
        ServerDef(4, "Best quality · up to 2160p · Soft subtitles"),
        ServerDef(5, "Reliable · 1080p · Sub + Dub"),
        ServerDef(6, "Fast · 720p · Sub + Dub"),
        ServerDef(7, "Fast · 1080p · Sub + Dub"),
        ServerDef(8, "Soft subtitles · 1080p · Sub + Dub"),
        ServerDef(9, "Soft subtitles · 1080p · Multi-language"),
    )

    fun description(number: Int): String =
        servers.firstOrNull { it.number == number }?.description ?: "Server $number"

    // 2.0-dev1.7: user-rearrangeable priority order. Default is the
    // existing 1..9 — easiest/reliable first, clearance-gated
    // server 3 lower — and that default is NOT changed. The order
    // lives in anipull_settings under "server_order" as comma-
    // separated numbers ("1,2,3,4,5,6,7,8,9"). A missing, empty
    // or stale value falls back to the default, and any server
    // missing from a stored value is appended in default order —
    // a bad value can never silently drop a server.
    val defaultOrder: List<Int> = (1..9).toList()

    fun orderedNumbers(context: Context): List<Int> {
        val raw = context.getSharedPreferences("anipull_settings", Context.MODE_PRIVATE)
            .getString("server_order", null) ?: return defaultOrder
        val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..9 }.distinct()
        if (parsed.isEmpty()) return defaultOrder
        return parsed + defaultOrder.filter { it !in parsed }
    }

    fun saveOrder(context: Context, order: List<Int>) {
        val clean = order.filter { it in 1..9 }.distinct()
        val prefs = context.getSharedPreferences("anipull_settings", Context.MODE_PRIVATE)
        if (clean.isEmpty()) prefs.edit().remove("server_order").apply()
        else prefs.edit().putString("server_order", clean.joinToString(",")).apply()
    }

    // Build one server's source. `paheCookie`/`paheUa` are only used
    // for server 3 — an already-obtained clearance, passed in as a
    // plain string; the engine never solves a challenge itself.
    // Unknown number -> null.
    fun build(number: Int, paheCookie: String?, paheUa: String?): EpisodeSource? = when (number) {
        1 -> EpisodeSources.aniZone()
        2 -> EpisodeSources.justAnime()
        3 -> EpisodeSources.pahe(paheCookie, paheUa)
        4 -> EpisodeSources.aniPm()
        5 -> EpisodeSources.nino2()
        6 -> EpisodeSources.aniWaves()
        7 -> EpisodeSources.anikoto()
        8 -> EpisodeSources.animeStream()
        9 -> EpisodeSources.anv()
        else -> null
    }
}
