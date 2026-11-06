package com.js.anipull

// Stage 1 demo data only — copied from the web mockup's 15-title catalog.
// Nothing here is fetched from any API, and episode counts are demo values
// except Frieren S1 (28), which the web demo already used.
data class DemoTitle(
    val title: String,
    val year: String,
    val format: String,
    val episodes: Int,
    val badge: String = "",
)

val DEMO_TITLES = listOf(
    DemoTitle("Frieren: Beyond Journey's End", "2023", "TV", 28),
    DemoTitle("Attack on Titan", "2013", "TV", 12),
    DemoTitle("Demon Slayer: Kimetsu no Yaiba", "2019", "TV", 12),
    DemoTitle("Jujutsu Kaisen", "2020", "TV", 12),
    DemoTitle("Death Note", "2006", "TV", 12),
    DemoTitle("Cyberpunk: Edgerunners", "2022", "ONA", 10),
    DemoTitle("Black Clover", "2017", "TV", 12),
    DemoTitle("Re:ZERO — Starting Life in Another World", "2016", "TV", 12),
    DemoTitle("Solo Leveling", "2024", "TV", 12),
    DemoTitle("SPY x FAMILY", "2022", "TV", 12),
    DemoTitle("Hunter x Hunter (2011)", "2011", "TV", 12),
    DemoTitle("The Apothecary Diaries", "2023", "TV", 12),
    DemoTitle("Chainsaw Man", "2022", "TV", 12),
    DemoTitle("Steins;Gate 0", "2018", "TV", 12),
    DemoTitle("A Silent Voice", "2016", "Movie", 1),
)

val WEEKEND_PICKS = DEMO_TITLES.take(5)
val TRENDING = DEMO_TITLES.drop(2).take(5)
val POPULAR = DEMO_TITLES.drop(5).take(5)
val SEASON_PICKS = DEMO_TITLES.drop(8).take(5)
val TOP_ANIME = DEMO_TITLES.take(5).reversed()
val MADHOUSE_SPOTLIGHT = listOf(
    DEMO_TITLES[4], // Death Note
    DEMO_TITLES[10], // Hunter x Hunter (2011)
    DEMO_TITLES[0], // Frieren
)

fun episodesFor(title: DemoTitle, season: Int): List<Int> {
    // Frieren S2 is TBD in the web demo too: no episodes, sheet is blocked.
    if (title.title.startsWith("Frieren") && season == 2) return emptyList()
    return (1..title.episodes).toList()
}

