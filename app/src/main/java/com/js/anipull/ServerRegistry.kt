package com.js.anipull

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
