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
// - Bounded: the last MAX_ENTRIES (200) entries are kept, in memory
//   and in the file — the log can never grow forever.
// - report(context) returns the full self-contained plain-text
//   report (app version, device/Android, every entry oldest first)
//   for Copy; clear() wipes memory + file.
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
    private const val DIR_NAME = "download_logs"
    private const val FILE_NAME = "download_log.txt"

    private val lock = Any()
    private val buffer = ArrayDeque<DownloadLogEntry>()
    private var appContext: Context? = null
    private var loadedFromFile = false

    fun init(context: Context) {
        val ctx = context.applicationContext
        var needFlush = false
        synchronized(lock) {
            appContext = ctx
            if (!loadedFromFile) {
                loadedFromFile = true
                // Merge the persisted tail under the in-memory
                // entries (same process may already have logged
                // resolve attempts before any init ran).
                val persisted = readFileEntries(ctx)
                val merged = (persisted + buffer.toList()).takeLast(MAX_ENTRIES)
                buffer.clear()
                buffer.addAll(merged)
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
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
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

    fun clear(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) { buffer.clear() }
        try {
            File(File(ctx.filesDir, DIR_NAME), FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    fun logTest(context: Context) {
        init(context)
        log("TEST", "Test entry — the download pipeline did not run. Real entries appear here when a download is attempted.")
    }

    // Full self-contained report: header (app + device) + every
    // entry oldest first, so the last job's stage trail reads top
    // to bottom. No URLs with query params, no cookie values.
    fun report(context: Context): String {
        init(context)
        val entries = entries(context)
        val version = appVersion(context)
        return buildString {
            appendLine("Anipull download log — TEMPORARY debug report")
            appendLine("App: ${version.first} (versionCode ${version.second}, ${context.packageName})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Entries: ${entries.size} (kept: last $MAX_ENTRIES)")
            appendLine("---")
            if (entries.isEmpty()) {
                appendLine("No download log entries yet — try a download, then come back here.")
            } else {
                entries.forEach { appendLine(it.toLine()) }
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

    private fun readFileEntries(context: Context): List<DownloadLogEntry> {
        return try {
            val file = logFile(context)
            if (!file.exists()) return emptyList()
            file.readLines().mapNotNull { DownloadLogEntry.fromLine(it) }.takeLast(MAX_ENTRIES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendToFile(context: Context, entry: DownloadLogEntry) {
        try {
            val file = logFile(context)
            file.appendText(entry.toLine() + "\n")
            // Trim the file to the same bound as the buffer, so a
            // long session cannot grow it without limit. Trimming
            // rewrites the whole (small, <=200-line) file.
            val lines = file.readLines()
            if (lines.size > MAX_ENTRIES) {
                file.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
            // Logging must never break a download.
        }
    }

    private fun writeFile(context: Context) {
        try {
            val lines = synchronized(lock) { buffer.map { it.toLine() } }
            logFile(context).writeText(
                if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n",
            )
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
                    if (entries.isEmpty()) "No download attempts logged yet" else "${entries.size} entr${if (entries.size == 1) "y" else "ies"} saved (last ${DownloadLogs.MAX_ENTRIES} kept)",
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
                "The copied report lists these oldest first, with the app version and device at the top.",
                color = Faint, fontSize = 12.sp,
            )
        }
    }
}
