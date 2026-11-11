package com.js.anipull

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- 2.0-dev2.4: Recently Downloaded home row --------------------------
// Derived ONLY from DownloadEngine.jobs — DownloadEngine and
// DownloadJob are never modified here. Every job state shows
// (succeeded, errored, cancelled), per JS: the row is "what you
// recently tried to pull", not "what finished".

// One anime in the row: the ApiTitle to open, plus the highest
// episode number downloaded for it (the detail auto-scroll target).
data class RecentAnime(
    val key: String,
    val title: ApiTitle,
    val lastEpisode: Int,
)

// Group jobs by anime, most recent first. Enqueue appends new jobs
// at the end of the list (and a retry replaces + appends), so the
// reversed list is recency order: the first sighting of an anime
// wins its title/poster, and the max episode is tracked across
// all of that anime's jobs. titleId <= 0 (pre-context jobs) falls
// back to the anime title as the key, with a negative-hash id in
// the demoToApi style so PosterCard/detail still get a stable id.
fun recentlyDownloadedAnimes(jobs: List<DownloadJob>): List<RecentAnime> {
    val byKey = LinkedHashMap<String, RecentAnime>()
    for (job in jobs.asReversed()) {
        if (job.animeTitle.isBlank()) continue
        val key = if (job.titleId > 0) "id:${job.titleId}" else "title:${job.animeTitle}"
        val existing = byKey[key]
        if (existing == null) {
            val id = if (job.titleId > 0) job.titleId else -job.animeTitle.hashCode().toLong()
            byKey[key] = RecentAnime(
                key = key,
                title = ApiTitle(
                    id = id, title = job.animeTitle, poster = job.poster,
                    type = null, year = null, score = null, overview = null,
                    genres = null, status = null, episodes = null,
                ),
                lastEpisode = maxOf(job.episodeNumber, 0),
            )
        } else if (job.episodeNumber > existing.lastEpisode) {
            byKey[key] = existing.copy(lastEpisode = job.episodeNumber)
        }
    }
    return byKey.values.toList()
}

// ---- Dismissal: its own store, NOT the jobs ----------------------------
// SharedPreferences "anipull_recently", one string set of
// "<key>:<max episode at dismissal>". Dismissing NEVER deletes a
// job — it only hides the anime from this row. The anime reappears
// when a newer download for it arrives (current max episode >
// dismissed max episode) or was never dismissed.
private const val PREFS_RECENTLY = "anipull_recently"
private const val KEY_DISMISSED = "dismissed"

private fun loadDismissed(context: Context): Map<String, Int> {
    return try {
        context.getSharedPreferences(PREFS_RECENTLY, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISMISSED, emptySet())
            ?.mapNotNull { entry ->
                val sep = entry.lastIndexOf(':')
                if (sep <= 0) return@mapNotNull null
                val ep = entry.substring(sep + 1).toIntOrNull() ?: return@mapNotNull null
                entry.substring(0, sep) to ep
            }?.toMap() ?: emptyMap()
    } catch (_: Exception) { emptyMap() }
}

class RecentDismissals(private val context: Context) {
    var dismissed by mutableStateOf(loadDismissed(context))
        private set

    fun isVisible(recent: RecentAnime): Boolean {
        val atDismissal = dismissed[recent.key] ?: return true
        return recent.lastEpisode > atDismissal
    }

    fun dismiss(recent: RecentAnime) {
        val next = dismissed + (recent.key to recent.lastEpisode)
        dismissed = next
        try {
            context.getSharedPreferences(PREFS_RECENTLY, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_DISMISSED, next.map { (k, ep) -> "$k:$ep" }.toSet())
                .apply()
        } catch (_: Exception) { }
    }
}

@Composable
fun rememberRecentDismissals(): RecentDismissals {
    val context = LocalContext.current
    return remember(context) { RecentDismissals(context) }
}

// ---- The row ------------------------------------------------------------
// Same header treatment as PosterRow: pink icon at left, 17sp bold
// HeadingFont. Cards are plain PosterCards in a Box so the dismiss
// cross can overlay the poster's top-right corner; the cross is its
// own clickable on top of the card, so tapping it never opens the
// detail. animateItem() lets the remaining posters glide
// left to fill the gap when one is dismissed.
@Composable
fun RecentlyDownloadedSection(
    recents: List<RecentAnime>,
    onOpen: (ApiTitle, Int) -> Unit,
    onDismiss: (RecentAnime) -> Unit,
) {
    if (recents.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_history),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                colorFilter = ColorFilter.tint(Pink),
            )
            Spacer(Modifier.width(8.dp))
            Text("Recently downloaded", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recents, key = { it.key }) { recent ->
                Box(Modifier.animateItem()) {
                    PosterCard(
                        recent.title,
                        onClick = { onOpen(recent.title, recent.lastEpisode) },
                        slot = "recently-${recent.title.id}",
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .clickable { onDismiss(recent) }
                            .semantics { contentDescription = "Remove ${recent.title.title} from Recently downloaded" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
