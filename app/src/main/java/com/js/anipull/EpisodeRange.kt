package com.js.anipull

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- 2.0-dev1.9: WHY ONE PIECE LAGGED (shonenX comparison) --------
// Read in code, not guessed: shonenX lib/features/discovery/
// presentation/widgets/episodes_panel/episode_list_panel.dart,
// episode_tiles.dart, shared/widgets/smart_image.dart.
//
// shonenX renders 1,180 episodes on "All" without lag because:
//  1. LAZY BUILDER. ListView.builder / GridView.builder — only the
//     ~12 viewport rows exist as widgets. "All" never builds 1,180
//     rows. Anipull rendered ALL rows in a plain Column
//     (EpisodeRowsColumn: rows.forEach { EpisodeListRow(...) },
//     grid: rows.chunked(2).forEach) sitting inside ONE LazyColumn
//     item of DetailScreen — so all 1,180 rows were composed,
//     measured and laid out at once (one ~80,000dp-tall item), on
//     first composition and on every recomposition. THIS was the
//     main lag, not the images.
//  2. CHUNKING IS NOT THE PERF MECHANISM. shonenX only shows the
//     range dropdown when >100 episodes, _chunkIndex = 0 ("All")
//     is the DEFAULT, and it only auto-jumps to the chunk holding
//     the watched/current episode. Chunking is navigation UX; the
//     lazy builder is what makes "All" cheap. So grouping alone
//     would NOT have fixed Anipull's "All" path — the rows had to
//     become lazy items of the scrolling list too (done here:
//     EpisodeList is now a LazyListScope extension, rows are
//     items(visibleRows, key = { it.number })).
//  3. SCOPED STATE. shonenX tiles are StatelessWidgets; per-row
//     state arrives as plain params, and providers are watched
//     with .select(...) so a progress/watch change rebuilds only
//     the affected tile. Anipull held selected/resolving/
//     resolvingServer/rowErrors as mutableStateOf in EpisodeList
//     AND collected DownloadEngine.jobs — every download-progress
//     tick recomposed EpisodeList, which re-ran the UNMEMOIZED
//     rows mapping (.map { ... }.distinctBy over 1,180 rows, a
//     plain val in the body) and recomposed the whole 1,180-row
//     Column. A long-press selection toggle did the same. Fixed
//     by memoizing rows (remember), and by passing each row only
//     its own derived params (job/isSelected/isResolving/rowError)
//     plus remember(row)-stable tap/long-press lambdas, so an
//     unchanged row skips recomposition.
//  4. IMAGES WERE NOT THE LIST-VIEW CAUSE. List rows are text-only
//     (no stills). The loader was already decent: loadPoster does
//     size-aware decode (sampleSizeFor), Semaphore(4) in-flight
//     cap, 100MB LRU PosterCache, 300ms network delay so a fling
//     never starts a fetch, disk cache. shonenX's equivalent is
//     cached_network_image (disk cache, maxWidthDiskCache 800,
//     memCacheWidth/Height, FilterQuality.low) with only visible
//     tiles loading. The one real image gap was grid view: all
//     1,180 PosterImage cells composed at once = 1,180
//     LaunchedEffects queueing loads. Lazy rows fix that too;
//     grid cells already decode thumbnail-sized (160dp target),
//     so NO AniLab.kt change was needed.
//  5. DEDUP matches: shonenX dedups in one pass with a map keyed
//     by episode number; Anipull's distinctBy { it.number } is the
//     same idea and is kept.
// Default range is 1–100 (JS, 2.0-dev2.5 — reversed the dev1.9
// default-All choice). shonenX defaults to All (chunk 0) and only
// auto-jumps to a chunk when there is a watched/current episode;
// Anipull is a downloader with no watched progress, so there is
// nothing to auto-jump to — the default is the first chunk instead.

// One entry in the episode range selector. min/max null = All.
data class EpisodeRange(val label: String, val min: Int?, val max: Int?, val count: Int)

// "All" first, then 100-episode chunks — only when the list is
// longer than 100 episodes, same threshold as shonenX. Ranges are
// built from the sorted distinct episode numbers, so gaps in
// numbering (a missing episode) never produce an empty range.
fun episodeRanges(numbers: List<Int>): List<EpisodeRange> {
    val sorted = numbers.distinct().sorted()
    if (sorted.isEmpty()) return emptyList()
    val ranges = mutableListOf(EpisodeRange("All", null, null, sorted.size))
    if (sorted.size > 100) {
        var i = 0
        while (i < sorted.size) {
            val end = minOf(i + 99, sorted.size - 1)
            val min = sorted[i]
            val max = sorted[end]
            ranges += EpisodeRange("$min–$max", min, max, end - i + 1)
            i += 100
        }
    }
    return ranges
}

// Range selector in Anipull's own pill language (same rounded pill
// + pink-selected treatment as EpisodeViewToggle, not a copy of
// shonenX's capsule): a pill showing the current range opens a
// dropdown of All / 1–100 / 101–200 / … with a per-range count,
// and the total count sits beside it ("1180 ep", shonenX-style).
@Composable
fun EpisodeRangeSelector(
    ranges: List<EpisodeRange>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
) {
    if (ranges.size <= 1) return
    var expanded by remember { mutableStateOf(false) }
    val current = ranges.getOrNull(selectedIndex) ?: ranges.first()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .border(1.dp, Pink.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                .background(Card)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("▾", color = Muted, fontSize = 12.sp)
            }
        }
        Text("${ranges[0].count} ep", color = Muted, fontSize = 12.sp)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Card),
        ) {
            ranges.forEachIndexed { idx, range ->
                val isCurrent = idx == selectedIndex
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                range.label,
                                color = if (isCurrent) Pink else TextMain,
                                fontSize = 13.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text("${range.count} ep", color = Muted, fontSize = 12.sp)
                        }
                    },
                    onClick = { expanded = false; onPick(idx) },
                )
            }
        }
    }
}
