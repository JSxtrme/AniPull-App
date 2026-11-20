package com.js.anipull

// 2.0-dev2.21 — HLS subtitle-playlist time mapping. Pure Kotlin
// (no Android APIs), so it can be checked off-device like
// Mp4WebVtt.
//
// THE BUG (Death Note E1, dev2.20): a subtitle media playlist is
// a list of ~6 s .vtt segments, one per EXTINF. The old code
// (buildWebVttFromPlaylist) threw away every '#' line — including
// the #EXTINF durations, the only thing that says where each
// segment sits in the episode — and stitched the segment texts
// together at face value. Segment cue times may be LOCAL (each
// chunk's clock restarts at 00:00:00.000; the pin to the media
// timeline is the X-TIMESTAMP-MAP header, which the old code
// never read) or ABSOLUTE (total display times, per RFC 8216
// §3.1.4). With local-timed segments, all 272 cues piled into
// the first ~6 s of the track and nothing showed after that —
// VLC listed the tracks, JS selected them, nothing rendered.
// The fix: parse each #EXTINF duration, keep a running offset,
// and shift each segment's cues by its offset when the segments
// are local-timed; leave them alone when they are already
// absolute (detected from the cue times themselves, so neither
// server style gets double-shifted).

// One cue, times in microseconds. HlsDownloader maps its
// SubtitleCue to/from this at the boundary.
data class TimedCue(val startUs: Long, val endUs: Long, val text: String)

// One entry of a subtitle media playlist: the segment URI as
// written in the playlist (resolved by the caller) and the
// segment's start time in the episode, from the summed #EXTINF
// durations of all preceding segments.
data class SubtitlePlaylistSegment(val uri: String, val startOffsetUs: Long)

// Parse a subtitle media playlist into its segments with their
// start offsets. An #EXTINF duration belongs to the segment URI
// that follows it; a segment with no (or an unparseable) EXTINF
// does not advance the offset.
internal fun parseSubtitlePlaylistSegments(playlistText: String): List<SubtitlePlaylistSegment> {
    val out = mutableListOf<SubtitlePlaylistSegment>()
    var offsetUs = 0L
    var pendingUs = 0L
    for (raw in playlistText.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF:")) {
            val dur = line.removePrefix("#EXTINF:").substringBefore(",").trim().toDoubleOrNull()
            if (dur != null && dur > 0) pendingUs = (dur * 1_000_000).toLong()
            continue
        }
        if (line.startsWith("#")) continue
        out += SubtitlePlaylistSegment(line, offsetUs)
        offsetUs += pendingUs
        pendingUs = 0L
    }
    return out
}

// True when the segments' cue times are already absolute (total
// display times): at least one later segment's cues sit at or
// after that segment's playlist offset. Local-timed segments
// have all their cues near zero no matter how large the offset
// is. The median cue start is used (not the first) so one
// boundary-spanning cue spilling in from the previous segment
// cannot flip the verdict.
internal fun subtitleCuesAreAbsolute(
    perSegmentCues: List<List<TimedCue>>,
    offsetsUs: List<Long>,
): Boolean {
    for (i in perSegmentCues.indices) {
        if (i == 0) continue
        val cues = perSegmentCues[i]
        if (cues.isEmpty()) continue
        val off = offsetsUs[i]
        if (off <= 0) continue
        val starts = cues.map { it.startUs }.sorted()
        val median = starts[starts.size / 2]
        if (median >= off - 1_500_000L) return true
    }
    return false
}

// Merge per-segment cue lists into one episode-timeline list:
// shift each segment's cues by its playlist offset when the
// segments are local-timed, leave them alone when absolute.
// Boundary-spanning cues are duplicated into adjacent segments
// (spec), so exact duplicates (same start, end and text) are
// dropped after sorting.
internal fun mergeSubtitleSegmentCues(
    perSegmentCues: List<List<TimedCue>>,
    offsetsUs: List<Long>,
): List<TimedCue> {
    val absolute = subtitleCuesAreAbsolute(perSegmentCues, offsetsUs)
    val merged = mutableListOf<TimedCue>()
    for (i in perSegmentCues.indices) {
        val shift = if (absolute) 0L else offsetsUs[i]
        for (c in perSegmentCues[i]) {
            merged += TimedCue(c.startUs + shift, c.endUs + shift, c.text)
        }
    }
    val sorted = merged.sortedBy { it.startUs }
    return sorted.filterIndexed { idx, c ->
        idx == 0 || !(sorted[idx - 1].startUs == c.startUs &&
            sorted[idx - 1].endUs == c.endUs &&
            sorted[idx - 1].text == c.text)
    }
}
