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

    override fun onCreate() {
        super.onCreate()
        DownloadEngine.init(this)
        startForegroundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadEngine.init(this)
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
                            stopSelf()
                            return@launch
                        }
                    } else {
                        DownloadNotifications.showServiceNotification(
                            this@DownloadService,
                            activeSummary(jobs),
                        )
                    }
                    delay(1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun activeSummary(jobs: List<DownloadJob>): String {
        val active = jobs.filter { it.state == JobState.DOWNLOADING || it.state == JobState.QUEUED }
        val downloading = active.count { it.state == JobState.DOWNLOADING }
        return when {
            downloading == 0 && active.isEmpty() -> "No active downloads"
            downloading == 0 -> "${active.size} download(s) queued"
            downloading == 1 -> {
                val job = active.first { it.state == JobState.DOWNLOADING }
                "Downloading ${job.animeTitle} — E${job.episodeNumber} · ${job.progress}%"
            }
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
        } catch (_: Exception) {
            // If the system refuses the foreground start (e.g. a
            // background-start restriction), the jobs still run in
            // the engine's scope — just without the service shield.
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
