package com.js.anipull

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 2.0-dev1.9 — TEMPORARY DEBUG ONLY: rolling in-app log of the
// ENTIRE download pipeline, so a failed download can be diagnosed
// from the phone instead of guessed at. WILL BE REMOVED once
// downloads work — do not build anything on top of this API.
//
// How it works (mirrors CrashLogs.kt):
// - log(stage, message) appends to an in-memory ring buffer AND,
//   once init(context) has run, to filesDir/download_logs/
//   download_log.txt, so entries survive app restarts and failed
//   jobs. init() is called from DownloadEngine.init,
//   DownloadService.onCreate and CloudflareClearance.ensureClearance,
//   so resolve-time entries (logged before any engine init) sit in
//   memory and are flushed to the file on the first init.
// - Bounded: the first HEAD_KEEP (50) entries AND the last
//   TAIL_KEEP (150) entries are kept, in memory and in the file —
//   200 total, so the log can never grow forever. The head pins
//   the log's start (RESOLVE lines from the first jobs), the tail
//   keeps the latest failures; the dropped middle is counted and
//   marked "[... N entries omitted in the middle ...]" in the
//   report and the file, so the gap is never silent.
// - report() pins a per-job summary at the top, derived from the
//   entries themselves (see buildJobSummaries), so the next pasted
//   log answers "which server and which host" even when 239
//   segment failures fill the tail.
// - report(context) returns the full self-contained plain-text
//   report (app version, device/Android, per-job summary, every
//   KEPT entry oldest first) for Copy; clear() wipes memory + file.
// - REDACTION: no secrets, no cookie values, no signed URLs.
//   sanitizeUrl() strips query params and fragments (host + path
//   only); sanitize() also scrubs cf_clearance values out of any
//   message. Call sites must still never pass a cookie value in.
// - No PII: job ids, server numbers, HTTP codes, byte counts and
//   error text only. Titles appear only as the job's anime title
//   (already on the user's own screen).

data class DownloadLogEntry(
    val timestampMs: Long,
    val stage: String,
    val message: String,
) {
    // One line, exactly as stored in the file.
    fun toLine(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        return "[$stamp] [$stage] $message"
    }

    companion object {
        fun fromLine(line: String): DownloadLogEntry? {
            // "[2026-11-11 01:23:45.678] [STAGE] message"
            if (!line.startsWith("[")) return null
            val stampEnd = line.indexOf(']')
            if (stampEnd < 0) return null
            val stamp = line.substring(1, stampEnd)
            val ms = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(stamp)?.time
            } catch (_: Exception) {
                null
            } ?: return null
            val rest = line.substring(stampEnd + 1).trim()
            if (!rest.startsWith("[")) return null
            val stageEnd = rest.indexOf(']')
            if (stageEnd < 0) return null
            return DownloadLogEntry(ms, rest.substring(1, stageEnd), rest.substring(stageEnd + 1).trim())
        }
    }
}

object DownloadLogs {
    const val MAX_ENTRIES = 200
    // 2.0-dev2.2 head+tail retention: the first HEAD_KEEP entries
    // (the log's start — RESOLVE lines included) are pinned and the
    // last TAIL_KEEP roll; everything in between is dropped and
    // counted. HEAD_KEEP + TAIL_KEEP == MAX_ENTRIES.
    const val HEAD_KEEP = 50
    const val TAIL_KEEP = 150
    private const val DIR_NAME = "download_logs"
    private const val FILE_NAME = "download_log.txt"

    private val lock = Any()
    private val buffer = ArrayDeque<DownloadLogEntry>()
    // Entries dropped from the middle by head+tail trimming (0 =
    // nothing dropped — the buffer is still a plain prefix).
    private var omittedMiddleCount = 0
    private var appContext: Context? = null
    private var loadedFromFile = false

    private fun omittedMarker(n: Int) =
        "[... $n ${if (n == 1) "entry" else "entries"} omitted in the middle ...]"

    private val omittedMarkerRegex =
        Regex("""\[\.\.\. (\d+) entr(?:y|ies) omitted in the middle \.\.\.\]""")

    private fun parseOmittedMarker(line: String): Int? =
        omittedMarkerRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // Head+tail trim of a full list: first HEAD_KEEP + last
    // TAIL_KEEP, plus how many were dropped from the middle.
    private fun trimHeadTail(list: List<DownloadLogEntry>): Pair<List<DownloadLogEntry>, Int> {
        if (list.size <= MAX_ENTRIES) return list to 0
        return (list.take(HEAD_KEEP) + list.takeLast(TAIL_KEEP)) to (list.size - MAX_ENTRIES)
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        var needFlush = false
        synchronized(lock) {
            appContext = ctx
            if (!loadedFromFile) {
                loadedFromFile = true
                // Merge the persisted entries under the in-memory
                // ones (same process may already have logged
                // resolve attempts before any init ran), keeping
                // head+tail — and carry the file's omitted count
                // (its middle gap is real even after a reload).
                val (persisted, persistedOmitted) = readFileEntries(ctx)
                val (merged, newlyOmitted) = trimHeadTail(persisted + buffer.toList())
                buffer.clear()
                buffer.addAll(merged)
                omittedMiddleCount = persistedOmitted + newlyOmitted
                needFlush = true
            }
        }
        if (needFlush) writeFile(ctx)
    }

    fun log(stage: String, message: String) {
        val entry = DownloadLogEntry(System.currentTimeMillis(), stage, sanitize(message))
        var ctx: Context? = null
        synchronized(lock) {
            buffer.addLast(entry)
            // Head+tail: once full, drop the oldest NON-head entry
            // (index HEAD_KEEP) — the first 50 stay pinned, the
            // last 150 roll, the middle gap grows by one.
            if (buffer.size > MAX_ENTRIES) {
                buffer.removeAt(HEAD_KEEP)
                omittedMiddleCount++
            }
            ctx = appContext
        }
        ctx?.let { appendToFile(it, entry) }
    }

    // Convenience: one call per job-stage, so the trail reads as
    // "[job dl_…] message" and the last job can be followed in a
    // 200-entry window shared by several jobs.
    fun logJob(jobId: String, stage: String, message: String) =
        log(stage, "[job $jobId] $message")

    fun entries(context: Context): List<DownloadLogEntry> {
        init(context)
        synchronized(lock) { return buffer.toList() }
    }

    // How many entries were dropped from the middle by head+tail
    // trimming (0 = none dropped yet).
    fun omittedCount(): Int = synchronized(lock) { omittedMiddleCount }

    fun clear(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) { buffer.clear(); omittedMiddleCount = 0 }
        try {
            File(File(ctx.filesDir, DIR_NAME), FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    fun logTest(context: Context) {
        init(context)
        log("TEST", "Test entry — the download pipeline did not run. Real entries appear here when a download is attempted.")
    }

    // Full self-contained report: header (app + device) + a
    // per-job summary + every kept entry oldest first, so the last
    // job's stage trail reads top to bottom. A "[... N entries
    // omitted in the middle ...]" line marks the head+tail gap.
    // No URLs with query params, no cookie values.
    fun report(context: Context): String {
        init(context)
        val entries = entries(context)
        val omitted = omittedCount()
        val version = appVersion(context)
        val summaries = buildJobSummaries(entries)
        return buildString {
            appendLine("Anipull download log — TEMPORARY debug report")
            appendLine("App: ${version.first} (versionCode ${version.second}, ${context.packageName})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Entries: ${entries.size} kept (first $HEAD_KEEP + last $TAIL_KEEP${if (omitted > 0) ", $omitted omitted in the middle" else ""})")
            appendLine("---")
            append(jobSummaryText(summaries))
            appendLine("---")
            if (entries.isEmpty()) {
                appendLine("No download log entries yet — try a download, then come back here.")
            } else {
                entries.forEachIndexed { index, entry ->
                    if (omitted > 0 && index == HEAD_KEEP && entries.size > HEAD_KEEP) appendLine(omittedMarker(omitted))
                    appendLine(entry.toLine())
                }
            }
            appendLine("---")
            appendLine("End of report. Paste this whole text when reporting a failed download.")
        }
    }

    fun appVersion(context: Context): Pair<String, Long> {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            (info.versionName ?: "?") to code
        } catch (_: Exception) {
            "?" to 0L
        }
    }

    // --- Per-job summary (2.0-dev2.2) --------------------------------
    //
    // Derived from the log entries themselves — no new state, no new
    // per-segment lines. The summary is what the next pasted log
    // must answer: which job, which server (number + internal
    // sub-server name — debug report only, never app UI), which
    // playlist/segment hosts, which headers went out with the
    // segment fetches, and what failed first with which HTTP code.
    //
    // Sources (all already in the log — nothing invented):
    // - ENGINE "[job X] enqueue: <title> E<n> <quality> <Sub|Dub>,
    //   server=N, link=<kind>" gives job id, title, episode,
    //   quality/audio, winning server number, link kind.
    // - The enqueue/runJob server number gives the sub-server
    //   name via serverName() (2.0-dev2.9 (D)) — never parsed
    //   from a RESOLVE OK line, so a stale warm won by another
    //   server can no longer mislabel the job.
    // - RESOLVE "start: ep <n> <jpn|eng> <quality>" + the OK line
    //   whose server matches the enqueue server gives the playlist
    //   host and the resolved link URL. A direct-resolve job has
    //   no RESOLVE lines, so those stay "unknown"/blank — but its
    //   sub-server name still comes from its server number.
    // - HLS "start: master=<url> … referer=<v|none> origin=<v|none>"
    //   gives the playlist host (again) and the headers sent on
    //   segment fetches. Lines from before dev2.2 have no referer/
    //   origin fields — those read "not logged", not "none".
    // - HLS "media playlist: <n> segments, …, first segment
    //   host=<host>" gives the segment host + segment count.
    //   DDL jobs have no segments: segment host is "n/a".
    // - First "FAILED"/"aborted:" line in the job's window gives
    //   the first failure; its HTTP code comes from the "HTTP <nnn>"
    //   in that line. The job's last decisive ENGINE line gives
    //   the outcome.
    data class JobSummary(
        val jobId: String,
        val title: String,
        val episodeNumber: Int,
        val quality: String,
        val audio: String,
        val serverNumber: Int,
        val subServer: String,
        val linkKind: String,
        // 2.0-dev2.6 (D): the resolved link URL itself (host+path
        // only — already query-stripped at log time), from the
        // winning server's OK line (or the DDL GET line). Blank
        // when the log genuinely has no URL for the job.
        val linkUrl: String,
        val playlistHost: String,
        val segmentHost: String,
        val segmentTotal: Int?,
        val referer: String,
        val origin: String,
        val firstFailure: String?,
        val firstFailureCode: String?,
        val outcome: String,
    )

    private val jobIdRegex = Regex("""^\[job (\S+)\] (.*)$""")
    // 2.0-dev2.6 (D): the enqueue quality has NO "p" suffix in the
    // real line ("enqueue: Death Note E1 1080 Sub, ...") — the old
    // (\d+p) never matched, so every summary read "unknown" for a
    // job the entries clearly identified. (\d+p?) takes both.
    private val enqueueRegex = Regex("""enqueue: (.+) E(\d+) (\d+p?) (Sub|Dub), server=(\d+), link=([^,]+)""")
    private val runJobRegex = Regex("""runJob start: server=(\d+), link=([^,]+), quality=(\S+) (\S+)""")
    private val resolveStartRegex = Regex("""start: ep (\d+) (\S+) (\S+), candidates=""")
    // 2.0-dev2.6 (D): the real OK line carries the offered height
    // between the kind and the URL ("— DDL mp4, offered 1080p
    // (https://…)") — the old regex required the URL immediately
    // after the kind, so it never matched and sub-server /
    // playlist host stayed "unknown".
    private val resolveOkRegex = Regex("""Server (\d+)(?: \(([^)]+)\))?: OK in \d+ms — (HLS master|DDL mp4)(?:, offered \d+p)? \((https?://[^)]+)\)""")
    // Job ids carry episode/audio/quality themselves
    // (dl_<titleId>_<ep>_<jpn|eng>_<quality>) — the fallback when
    // the enqueue line was trimmed out of the kept window.
    private val jobIdPartsRegex = Regex("""^dl_\d+_(\d+)_([a-z]+)_(\S+)$""")
    private val hlsStartRegex = Regex("""start: master=(\S+) quality=(\S+) audio=(\S+)(?: referer=(\S+) origin=(\S+))?""")
    private val mediaPlaylistRegex = Regex("""media playlist: (\d+) segments,.*first segment host=(\S+)""")
    private val mediaPlaylistOldRegex = Regex("""media playlist: (\d+) segments""")
    private val ddlGetRegex = Regex("""GET (https?://\S+) -> HTTP (\d+)""")
    private val httpCodeRegex = Regex("""HTTP (\d{3})""")

    // 2.0-dev2.9 (D): server number -> internal sub-server name,
    // the ONLY place a summary gets a name — derived from the
    // enqueue/runJob server number, never parsed from a log line.
    // Mirror of ServerRegistry.build() 1..9 and each resolver's
    // sourceName (AniZoneResolver 1, JustAnimeResolver 2,
    // PaheResolver 3, AniPmResolver 4, Nino2Resolver 5,
    // AniWavesResolver 6, AnikotoResolver 7, AnimeStreamResolver 8,
    // AnvResolver 9): if a source is renamed or renumbered there,
    // update this map in the same change. Unknown number (0 = no
    // enqueue/runJob line) stays "unknown", never a guessed name.
    private fun serverName(number: Int): String = when (number) {
        1 -> "AniZone"
        2 -> "JustAnime"
        3 -> "AnimePahe"
        4 -> "AniPm"
        5 -> "Nino2"
        6 -> "AniWaves"
        7 -> "Anikoto"
        8 -> "AnimeStream"
        9 -> "Anv"
        else -> "unknown"
    }

    private fun hostOf(url: String): String? = try {
        java.net.URL(url).host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        url.substringAfter("://", "").substringBefore("/").substringBefore(":").takeIf { it.isNotBlank() }
    }

    private fun jobIdOf(message: String): String? =
        jobIdRegex.find(message)?.groupValues?.getOrNull(1)

    private fun buildJobSummaries(entries: List<DownloadLogEntry>): List<JobSummary> {
        val jobIds = entries.mapNotNull { jobIdOf(it.message) }.distinct()
        return jobIds.takeLast(5).mapNotNull { summarizeJob(it, entries) }
    }

    private fun summarizeJob(jobId: String, entries: List<DownloadLogEntry>): JobSummary? {
        // Job-tagged lines (ENGINE/DDL), in order, with indices.
        val tagged = entries.mapIndexedNotNull { index, entry ->
            jobIdOf(entry.message)?.takeIf { it == jobId }?.let { index to entry }
        }
        if (tagged.isEmpty()) return null
        val enqueueEntry = tagged.firstOrNull { (_, e) -> e.message.contains("enqueue: ") }
        val enqueueIdx = enqueueEntry?.first ?: -1

        var title = "unknown"
        var episode = 0
        var quality = "?"
        var audio = "?"
        var server = 0
        var linkKind = "unknown"
        enqueueEntry?.second?.message?.let { msg ->
            enqueueRegex.find(msg)?.let { m ->
                title = m.groupValues[1]
                episode = m.groupValues[2].toIntOrNull() ?: 0
                quality = m.groupValues[3]
                audio = m.groupValues[4]
                server = m.groupValues[5].toIntOrNull() ?: 0
                linkKind = m.groupValues[6].trim()
            }
        }
        if (enqueueIdx < 0) {
            // No enqueue line (e.g. job restored after a restart):
            // runJob still has server / link / quality / audio.
            tagged.firstOrNull { (_, e) -> e.message.contains("runJob start:") }?.second?.message?.let { msg ->
                runJobRegex.find(msg)?.let { m ->
                    server = m.groupValues[1].toIntOrNull() ?: server
                    linkKind = m.groupValues[2].trim()
                    quality = m.groupValues[3]
                    audio = m.groupValues[4]
                }
            }
        }
        // 2.0-dev2.6 (D): whatever the enqueue/runJob lines did
        // not supply, the job id itself carries (episode, audio
        // code, quality) — so a job whose enqueue line was trimmed
        // out of the kept window still resolves its episode
        // instead of reading "episode ?". Title and server have no
        // job-id source and stay "unknown" when genuinely absent.
        if (episode == 0 || quality == "?" || audio == "?") {
            jobIdPartsRegex.find(jobId)?.let { m ->
                if (episode == 0) episode = m.groupValues[1].toIntOrNull() ?: 0
                if (audio == "?") audio = if (m.groupValues[2] == "eng") "Dub" else "Sub"
                if (quality == "?") quality = m.groupValues[3]
            }
        }
        val isHls = linkKind.contains("HLS", ignoreCase = true)
        val isDdl = linkKind.contains("DDL", ignoreCase = true) || linkKind.contains("mp4", ignoreCase = true)

        // --- RESOLVE block: the start line matching this job's
        // episode/audio/quality, then its OK line (URL + host).
        // 2.0-dev2.9 (D): the sub-server name comes from the
        // enqueue server number via serverName() above — never
        // from an OK line — so a stale warm won by another
        // server can no longer mislabel this job.
        val subServer = serverName(server)
        var playlistHost = "unknown"
        var linkUrl = ""
        if (episode > 0) {
            val audioLower = when (audio) { "Sub" -> "jpn"; "Dub" -> "eng"; else -> audio.lowercase() }
            val searchEnd = if (enqueueIdx >= 0) enqueueIdx else entries.size
            var resolveStartIdx = -1
            for (i in searchEnd - 1 downTo 0) {
                val e = entries[i]
                if (e.stage != "RESOLVE") continue
                val m = resolveStartRegex.find(e.message) ?: continue
                if ((m.groupValues[1].toIntOrNull() ?: -1) == episode &&
                    m.groupValues[2] == audioLower && m.groupValues[3] == quality
                ) { resolveStartIdx = i; break }
            }
            if (resolveStartIdx >= 0) {
                // 2.0-dev2.6 (D): the race logs an OK line for
                // EVERY server that returned a link (a sub-floor
                // win is parked, not the winner), so the FIRST ok
                // is not necessarily this job's winner. Take ONLY
                // the OK line whose server matches the enqueue
                // server — that is the link the job actually
                // downloaded. 2.0-dev2.9 (D): NEVER fall back to
                // the first OK — the matched block may be a stale
                // warm (confirm hit ResolveCache, no fresh resolve
                // ran), so a mismatched OK would borrow another
                // server's URL/host. No match leaves linkUrl /
                // playlistHost unknown here; the job's own HLS
                // start / DDL GET lines below still supply the
                // host when they exist.
                val okLines = ArrayList<kotlin.text.MatchResult>()
                for (i in resolveStartIdx + 1 until searchEnd) {
                    val e = entries[i]
                    if (e.stage != "RESOLVE") continue
                    if (e.message.startsWith("start:")) break // next resolve block
                    resolveOkRegex.find(e.message)?.let { okLines += it }
                }
                val ok = okLines.firstOrNull { it.groupValues[1].toIntOrNull() == server && server != 0 }
                ok?.let { m ->
                    linkUrl = sanitizeUrl(m.groupValues[4])
                    playlistHost = hostOf(m.groupValues[4]) ?: "unknown"
                }
            }
        }

        // --- HLS/DDL window: from enqueue to the job's last
        // tagged line (its terminal ENGINE line). HLS lines carry
        // no job id, so the window is chronological.
        val windowStart = if (enqueueIdx >= 0) enqueueIdx else tagged.first().first
        val windowEnd = tagged.last().first
        var referer = "not logged (older build)"
        var origin = "not logged (older build)"
        var segmentHost = if (isDdl) "n/a" else "unknown"
        var segmentTotal: Int? = null
        var firstFailure: String? = null
        // Two jobs can download in parallel, so HLS lines in this
        // window may belong to the other job: only trust lines
        // whose quality/audio match this job (the HLS start line
        // carries both). Lines after a matched start belong to
        // this job; if no start line survived in the window at all
        // (trimmed away), fall back to accepting them — and the
        // job-tagged ENGINE FAILED fallback below covers the rest.
        var hlsMatched = false
        var anyHlsStartSeen = false
        for (i in windowStart..windowEnd) {
            val e = entries[i]
            if (e.stage != "HLS") continue
            hlsStartRegex.find(e.message)?.let { m ->
                anyHlsStartSeen = true
                val qualityMatches = quality == "?" || m.groupValues[2] == quality
                val audioMatches = audio == "?" || m.groupValues[3] == audio
                if (qualityMatches && audioMatches) {
                    hlsMatched = true
                    hostOf(m.groupValues[1])?.let { playlistHost = it }
                    if (m.groupValues[4].isNotBlank()) referer = m.groupValues[4]
                    if (m.groupValues[5].isNotBlank()) origin = m.groupValues[5]
                }
            }
            val belongsToJob = hlsMatched || !anyHlsStartSeen
            if (belongsToJob) {
                if (firstFailure == null && (e.message.startsWith("FAILED ") || e.message.startsWith("aborted:"))) {
                    firstFailure = e.message.removePrefix("FAILED ")
                }
                if (segmentTotal == null) {
                    mediaPlaylistRegex.find(e.message)?.let { m ->
                        segmentTotal = m.groupValues[1].toIntOrNull()
                        segmentHost = m.groupValues[2]
                    } ?: mediaPlaylistOldRegex.find(e.message)?.let { m ->
                        segmentTotal = m.groupValues[1].toIntOrNull()
                    }
                }
            }
        }
        // DDL playlist host fallback: the DDL GET line carries it.
        if (isDdl && (playlistHost == "unknown" || linkUrl.isBlank())) {
            tagged.firstOrNull { (_, e) -> e.stage == "DDL" }?.second?.message?.let { msg ->
                ddlGetRegex.find(msg)?.let { m ->
                    if (playlistHost == "unknown") playlistHost = hostOf(m.groupValues[1]) ?: playlistHost
                    if (linkUrl.isBlank()) linkUrl = sanitizeUrl(m.groupValues[1])
                }
            }
        }
        // First failure fallback: a job-tagged ENGINE FAILED line
        // (covers DDL failures and jobs whose HLS stage never ran).
        if (firstFailure == null) {
            tagged.firstOrNull { (_, e) -> e.message.contains("FAILED:") }?.second?.message?.let { msg ->
                firstFailure = msg.substringAfter("] ", msg).substringAfter("FAILED: ").trim()
            }
        }
        val firstFailureCode = firstFailure?.let { httpCodeRegex.find(it)?.groupValues?.getOrNull(1) }

        // --- Outcome: the job's last decisive ENGINE line wins.
        var outcome = "in progress / unknown"
        for ((_, e) in tagged) {
            val msg = e.message
            when {
                msg.contains("COMPLETED:") -> outcome = "COMPLETED"
                msg.contains("final failure (no auto-retry left):") ->
                    outcome = "FAILED — " + msg.substringAfter("final failure (no auto-retry left): ").trim()
                msg.contains("GATED:") -> outcome = "PAUSED — waiting for Wi-Fi"
                msg.contains("CANCELLED by user") -> outcome = "CANCELLED"
                msg.contains("auto-retry ") ->
                    outcome = "FAILED — retrying (" + msg.substringAfter("auto-retry ").trim() + ")"
                msg.contains("FAILED:") ->
                    outcome = "FAILED — " + msg.substringAfter("FAILED: ").trim()
            }
        }

        return JobSummary(
            jobId = jobId, title = title, episodeNumber = episode,
            quality = quality, audio = audio, serverNumber = server,
            subServer = subServer, linkKind = linkKind, linkUrl = linkUrl,
            playlistHost = playlistHost, segmentHost = segmentHost,
            segmentTotal = segmentTotal, referer = referer, origin = origin,
            firstFailure = firstFailure, firstFailureCode = firstFailureCode,
            outcome = outcome,
        )
    }

    private fun jobSummaryText(summaries: List<JobSummary>): String = buildString {
        appendLine("PER-JOB SUMMARY (derived from the entries below — sub-server names are internal debug info, never shown in the app UI)")
        if (summaries.isEmpty()) {
            appendLine("No job entries yet — start a download and its summary will appear here.")
            return@buildString
        }
        val latest = summaries.last()
        appendLine("Most recent job:")
        appendLine("  Job: ${latest.jobId}")
        appendLine("  Title: ${latest.title} — episode ${if (latest.episodeNumber > 0) latest.episodeNumber else "?"}")
        appendLine("  Quality/audio: ${latest.quality} ${latest.audio}")
        appendLine("  Winning server: Server ${latest.serverNumber} (${latest.subServer})")
        appendLine("  Link: ${latest.linkKind}${if (latest.linkUrl.isNotBlank()) " (${latest.linkUrl})" else ""}")
        appendLine("  Playlist host: ${latest.playlistHost}")
        appendLine("  Segment host: ${latest.segmentHost}${latest.segmentTotal?.let { " ($it segments)" } ?: ""}")
        if (latest.linkKind.contains("HLS", ignoreCase = true)) {
            appendLine("  Headers on segment fetches: Referer: ${latest.referer} · Origin: ${latest.origin}")
        } else {
            appendLine("  Headers on segment fetches: n/a (no segments — direct download)")
        }
        // Don't repeat the code when the failure text already
        // carries it ("segment 0: Segment 0 HTTP 403 (HTTP 403)"
        // would be noise).
        val failureCodeSuffix = latest.firstFailureCode?.takeIf { code ->
            latest.firstFailure?.contains("HTTP $code") != true
        }?.let { " (HTTP $it)" }.orEmpty()
        appendLine("  First failure: ${latest.firstFailure ?: "none"}$failureCodeSuffix")
        appendLine("  Outcome: ${latest.outcome}")
        if (summaries.size > 1) {
            appendLine("Older jobs (one line each):")
            summaries.dropLast(1).forEach { s ->
                appendLine("  - ${s.jobId} | ${s.title} E${if (s.episodeNumber > 0) s.episodeNumber else "?"} | Server ${s.serverNumber} (${s.subServer}) | ${s.linkKind} | ${s.outcome}${s.firstFailureCode?.let { " | first failure HTTP $it" } ?: ""}")
            }
        }
    }

    // --- Redaction -----------------------------------------------------

    // Host + path only: everything from '?' or '#' on is dropped.
    // A bare URL with no path keeps just scheme + host.
    fun sanitizeUrl(url: String): String {
        val cut = url.substringBefore('?').substringBefore('#')
        return cut.trimEnd('/')
    }

    // Scrub signed URLs and cookie values out of free text: every
    // http(s) URL loses its query/fragment, cf_clearance values
    // become [REDACTED]. Call sites must still never pass a raw
    // cookie — this is the safety net, not the plan.
    fun sanitize(message: String): String {
        var out = message
        out = Regex("""https?://[^\s"'<>]+""").replace(out) { sanitizeUrl(it.value) }
        out = Regex("""cf_clearance=[^;\s"',]+""").replace(out, "cf_clearance=[REDACTED]")
        return out
    }

    // --- File backing ---------------------------------------------------

    private fun logFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)

    // Entries + the omitted-middle count recorded by the marker
    // line (if any) — the gap survives a restart. No trimming
    // here: callers decide (init merges, append trims).
    private fun readFileEntries(context: Context): Pair<List<DownloadLogEntry>, Int> {
        return try {
            val file = logFile(context)
            if (!file.exists()) return emptyList<DownloadLogEntry>() to 0
            var omitted = 0
            val entries = mutableListOf<DownloadLogEntry>()
            file.readLines().forEach { line ->
                parseOmittedMarker(line)?.let { omitted = it }
                    ?: DownloadLogEntry.fromLine(line)?.let { entries += it }
            }
            entries to omitted
        } catch (_: Exception) {
            emptyList<DownloadLogEntry>() to 0
        }
    }

    private fun appendToFile(context: Context, entry: DownloadLogEntry) {
        try {
            val file = logFile(context)
            file.appendText(entry.toLine() + "\n")
            // Trim the file to the same head+tail bound as the
            // buffer, so a long session cannot grow it without
            // limit. Trimming rewrites the whole (small,
            // <=200-line) file, with the omission marker between
            // head and tail. The previously omitted count carries
            // over — the gap only grows.
            val (entries, prevOmitted) = readFileEntries(context)
            if (entries.size > MAX_ENTRIES) {
                val (kept, dropped) = trimHeadTail(entries)
                writeFileLines(file, kept, prevOmitted + dropped)
            }
        } catch (_: Exception) {
            // Logging must never break a download.
        }
    }

    private fun writeFileLines(file: File, entries: List<DownloadLogEntry>, omitted: Int) {
        file.writeText(buildString {
            entries.forEachIndexed { index, entry ->
                if (omitted > 0 && index == HEAD_KEEP && entries.size > HEAD_KEEP) appendLine(omittedMarker(omitted))
                appendLine(entry.toLine())
            }
        })
    }

    private fun writeFile(context: Context) {
        try {
            val (lines, omitted) = synchronized(lock) { buffer.toList() to omittedMiddleCount }
            if (lines.isEmpty()) {
                logFile(context).writeText("")
            } else {
                writeFileLines(logFile(context), lines, omitted)
            }
        } catch (_: Exception) {
        }
    }
}

// 2.0-dev1.9 — TEMPORARY DEBUG ONLY (see DownloadLogs): the
// Settings > DEBUGGING > Download Logs page. Shows the recent
// entries newest first (same order as Crash Logs), with Copy
// report (the ENTIRE report as labelled plain text in one tap)
// and Clear. Reachable without any download succeeding; entries
// persist in filesDir, so a failed job is still here after a
// restart.
@Composable
fun DownloadLogsPage() {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(DownloadLogs.entries(ctx)) }
    // Re-read on every composition (refresh() recomposes): the
    // omitted-middle count changes as the log fills.
    val omitted = DownloadLogs.omittedCount()
    fun refresh() { entries = DownloadLogs.entries(ctx) }
    fun copyReport() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Anipull download log", DownloadLogs.report(ctx)))
        Toast.makeText(ctx, "Report copied", Toast.LENGTH_SHORT).show()
    }

    Column {
        SettingsSectionLabel("DOWNLOAD LOGS — TEMPORARY DEBUG")
        SettingsCard {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    if (entries.isEmpty()) "No download attempts logged yet" else "${entries.size} entr${if (entries.size == 1) "y" else "ies"} saved (first ${DownloadLogs.HEAD_KEEP} + last ${DownloadLogs.TAIL_KEEP} kept${if (omitted > 0) ", $omitted omitted in the middle" else ""})",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Temporary — debug only, will be removed once downloads work. Every step of a download is logged here (which server answered, what link it gave, playlist/segment progress, where the file went, and the exact failure). If a download fails, tap “Copy report” and paste the whole text — it says which stage broke. Logs stay only on your phone — never uploaded.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { copyReport() },
                        colors = ButtonDefaults.buttonColors(containerColor = Pink),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) { Text("Copy report", color = OnAccent, fontWeight = FontWeight.Bold, fontSize = 13.5.sp) }
                    OutlinedButton(
                        onClick = { DownloadLogs.logTest(ctx); refresh(); Toast.makeText(ctx, "Test entry added", Toast.LENGTH_SHORT).show() },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) { Text("Add test entry", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    if (entries.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { DownloadLogs.clear(ctx); refresh(); Toast.makeText(ctx, "Download logs cleared", Toast.LENGTH_SHORT).show() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) { Text("Clear", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (entries.isEmpty()) {
            SettingsCard {
                Column(modifier = Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("↓", color = Teal, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing logged yet — try downloading an episode.", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap “Add test entry” above to see how a report looks.", color = Muted, fontSize = 13.sp)
                }
            }
        } else {
            SettingsSectionLabel("RECENT ENTRIES — NEWEST FIRST")
            // Newest first on screen (same as Crash Logs); the
            // copied report is chronological, oldest first, so the
            // stage trail reads top to bottom. Plain Column, not a
            // nested LazyColumn — this page already lives inside
            // the Settings sub-page's LazyColumn.
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    entries.asReversed().forEach { entry ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs)) + "  [${entry.stage}]",
                                color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                entry.message,
                                color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
                            )
                            Spacer(Modifier.height(2.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "The copied report starts with a per-job summary, then lists these oldest first, with the app version and device at the top. Only the first 50 + last 150 entries are kept — anything omitted in the middle is marked in the report.",
                color = Faint, fontSize = 12.sp,
            )
        }
    }
}
