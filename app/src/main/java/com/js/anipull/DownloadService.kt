package com.js.anipull

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 2.0-dev1.0 ENGINE — foreground service (dataSync type) that runs
// DownloadEngine jobs. The engine does the downloading; this service
// exists so Android lets it keep running, and owns the persistent
// foreground notification. Per-episode progress lives in
// DownloadNotifications; this service only keeps the summary
// foreground notification fresh and stops itself when the queue
// goes quiet.
class DownloadService : Service() {

    companion object {
        const val FOREGROUND_ID = 2001
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private var watcher: Job? = null
    private var foregroundStarted = false
    // 2.0-dev2.5 DEFECT 1/1b: the watcher re-posted the foreground
    // notification every second with per-job progress text
    // ("Downloading … E1 · N%") — a second notification that also
    // re-animated on Nothing OS. The foreground notification is
    // now the static group summary (see Notifications.kt); the
    // watcher re-posts it ONLY when its count text changes.
    private var lastPostedSummary: String? = null

    override fun onCreate() {
        super.onCreate()
        // 2.0-dev1.9 TEMPORARY download diagnostics: init the log
        // here too — the service is the earliest engine entry point
        // on a cold start.
        DownloadLogs.init(this)
        DownloadEngine.init(this)
        DownloadLogs.log("SERVICE", "onCreate: service created")
        startForegroundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadEngine.init(this)
        DownloadLogs.log("SERVICE", "onStartCommand: startId=$startId")
        startForegroundIfNeeded()
        if (watcher == null) {
            watcher = scope.launch {
                // Grace period: a fresh enqueue takes a moment to
                // reach DOWNLOADING — don't stop on the first empty read.
                delay(2000)
                while (true) {
                    val jobs = DownloadEngine.jobs.value
                    val active = jobs.any { it.state == JobState.QUEUED || it.state == JobState.DOWNLOADING }
                    if (!active) {
                        // One more check after a pause, in case a job
                        // was just enqueued between reads.
                        delay(5000)
                        val stillQuiet = DownloadEngine.jobs.value.none {
                            it.state == JobState.QUEUED || it.state == JobState.DOWNLOADING
                        }
                        if (stillQuiet) {
                            DownloadLogs.log("SERVICE", "watcher: queue quiet for 7s — stopping service")
                            stopSelf()
                            return@launch
                        }
                    } else {
                        // Static count text only — no title, no %
                        // (per-job progress lives in the per-job
                        // notification). Re-post only on change.
                        val summary = activeSummary(jobs)
                        if (summary != lastPostedSummary) {
                            lastPostedSummary = summary
                            DownloadNotifications.showServiceNotification(
                                this@DownloadService,
                                summary,
                            )
                        }
                    }
                    delay(1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    // 2.0-dev2.5 DEFECT 1: count-only text. The old single-job
    // branch ("Downloading ${title} — E${n} · ${progress}%") put
    // per-job progress in the foreground notification — the second
    // notification. Titles and % now live only in the per-job
    // notification; this stays static so the shade entry sits
    // quietly with only its child's progress bar moving.
    private fun activeSummary(jobs: List<DownloadJob>): String {
        val active = jobs.filter { it.state == JobState.DOWNLOADING || it.state == JobState.QUEUED }
        val downloading = active.count { it.state == JobState.DOWNLOADING }
        return when {
            downloading == 0 && active.isEmpty() -> "Anipull downloads"
            downloading == 0 -> "${active.size} download(s) queued"
            downloading == 1 -> "Downloading 1 episode"
            else -> "Downloading $downloading episodes"
        }
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return
        foregroundStarted = true
        val notification = DownloadNotifications.buildServiceNotification(this, "Anipull downloads")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
            DownloadLogs.log("SERVICE", "foreground started (dataSync)")
        } catch (e: Exception) {
            // If the system refuses the foreground start (e.g. a
            // background-start restriction), the jobs still run in
            // the engine's scope — just without the service shield.
            DownloadLogs.log("SERVICE", "startForeground FAILED: ${e.javaClass.simpleName}: ${e.message ?: ""} (jobs still run in engine scope)")
            foregroundStarted = false
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
