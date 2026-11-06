package com.js.anipull

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

// 4.9 fix 9: Crash Logs, functional, styled like the rest of Anipull
// (Bg background, Card cards, Pink accent, 20dp corners, same
// section labels as Settings). No server, no upload — logs live
// ONLY on the user's phone, in filesDir/crash_logs/.
// How it works:
// - MainActivity installs install() on launch: it wraps the previous
//   default uncaught-exception handler, writes the crash to a file
//   SYNCHRONOUSLY (so it is flushed before the process dies), then
//   delegates to the previous handler, so the app still crashes
//   normally — it just leaves a report behind for next launch.
// - CrashLogsPage reads those files, newest first. A real crash
//   therefore appears here on the NEXT launch after it happened.
// - "Add test log" writes a non-fatal test entry, so the UI can be
//   checked without actually crashing the app.

data class CrashEntry(
    val fileName: String,
    val timestampMs: Long,
    val title: String,
    val message: String,
    val fullText: String,
)

object CrashLogs {
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeEntry(context.applicationContext, thread.name, throwable)
            } catch (_: Exception) {
                // Never break the real crash path because logging failed.
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else throw throwable
        }
    }

    fun writeEntry(context: Context, threadName: String, throwable: Throwable): File {
        val dir = File(context.filesDir, "crash_logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_${stamp}_${threadName.replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt")
        val text = buildString {
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Thread: $threadName")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message ?: ""}")
            appendLine("---")
            appendLine(throwable.stackTraceToString())
            throwable.cause?.let { appendLine("Caused by: ${it.javaClass.name}: ${it.message ?: ""}") }
        }
        file.writeText(text)
        return file
    }

    fun logTest(context: Context): File {
        val dir = File(context.filesDir, "crash_logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_${stamp}_test.txt")
        file.writeText(
            "Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
            "Thread: main (test log, not a crash)\n" +
            "Exception: Test log: this is a sample entry to check the Crash Logs UI\n" +
            "---\n" +
            "Test log — the app did not crash. Real crashes are written here automatically on next launch after they happen.\n"
        )
        return file
    }

    fun list(context: Context): List<CrashEntry> {
        val dir = File(context.filesDir, "crash_logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val text = try { file.readText() } catch (_: Exception) { "" }
                val lines = text.lines()
                val timeLine = lines.firstOrNull { it.startsWith("Time:") }?.removePrefix("Time:")?.trim()
                val excLine = lines.firstOrNull { it.startsWith("Exception:") }?.removePrefix("Exception:")?.trim() ?: "Unknown error"
                val isTest = file.name.contains("_test")
                CrashEntry(
                    fileName = file.name,
                    timestampMs = file.lastModified(),
                    title = if (isTest) "Test log" else excLine.substringBefore(":").substringAfterLast("."),
                    message = if (isTest) "Sample entry — app did not crash" else excLine,
                    fullText = text,
                )
            } ?: emptyList()
    }

    fun clear(context: Context) {
        File(context.filesDir, "crash_logs").listFiles()?.forEach { try { it.delete() } catch (_: Exception) { } }
    }
}

@Composable
fun CrashLogsPage() {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(CrashLogs.list(ctx)) }
    var expanded by remember { mutableStateOf<String?>(null) }
    fun refresh() { entries = CrashLogs.list(ctx) }
    fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Anipull crash log", text))
        Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    Column {
        SettingsSectionLabel("CRASH REPORTS")
        SettingsCard {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    if (entries.isEmpty()) "No crashes logged yet" else "${entries.size} report${if (entries.size == 1) "" else "s"} saved",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Uncaught crashes are saved here automatically, even if the app closes. A real crash appears here on the next launch after it happens. Logs stay only on your phone — never uploaded.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { CrashLogs.logTest(ctx); refresh(); Toast.makeText(ctx, "Test log added", Toast.LENGTH_SHORT).show() },
                        colors = ButtonDefaults.buttonColors(containerColor = Pink),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) { androidx.compose.material3.Text("Add test log", color = OnAccent, fontWeight = FontWeight.Bold, fontSize = 13.5.sp) }
                    if (entries.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { CrashLogs.clear(ctx); expanded = null; refresh(); Toast.makeText(ctx, "Crash logs cleared", Toast.LENGTH_SHORT).show() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) { androidx.compose.material3.Text("Clear all", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (entries.isEmpty()) {
            SettingsCard {
                Column(modifier = Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✓", color = Teal, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Good news — nothing has crashed since this build.", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap “Add test log” above to see how a report looks.", color = Muted, fontSize = 13.sp)
                }
            }
        } else {
            SettingsSectionLabel("REPORTS")
            entries.forEach { entry ->
                SettingsCard {
                    Column(modifier = Modifier.clickable { expanded = if (expanded == entry.fileName) null else entry.fileName }.padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF3B30).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) { androidx.compose.material3.Text("!", color = Color(0xFFFF3B30), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(entry.timestampMs)),
                                    color = Muted, fontSize = 12.5.sp,
                                )
                                Text(entry.message, color = Muted, fontSize = 12.5.sp, maxLines = 2)
                            }
                            Text(if (expanded == entry.fileName) "⌄" else "›", color = Muted, fontSize = 22.sp)
                        }
                        if (expanded == entry.fileName) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).background(Bg).padding(12.dp),
                            ) {
                                androidx.compose.material3.Text(
                                    entry.fullText, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { copy(entry.fullText) }) {
                                androidx.compose.material3.Text("Copy report", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
