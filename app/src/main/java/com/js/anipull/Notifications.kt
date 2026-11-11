package com.js.anipull

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// 1.9-dev4.8.1: the download notification DESIGN, built for real
// (not a mockup) so 2.0 just plugs a real download into it. Agreed
// design (2026-11-02): low-importance "Downloads" channel, per-episode
// progress + MB/s + Pause/Cancel, grouped summary for parallel (the
// preview below is single-episode, so no summary yet), completion
// ping that taps through to Downloads. The preview in DownloadsScreen
// drives this helper with a sample title and progress, and the
// Pause/Resume/Cancel actions below drive it back via shared state
// (isPaused / isCancelled) that the preview loop checks each tick.
//
// 2.0-dev1.0: per-job notifications (unique id per job), a grouped
// summary when >1 job is active, and DownloadActionReceiver routes
// actions carrying a job id to DownloadEngine. The legacy single-title
// path (PROGRESS_ID + static isPaused/isCancelled) is UNCHANGED —
// the 1.9 preview in DownloadsScreen still drives it exactly as before.
object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    const val PROGRESS_ID = 1001
    const val COMPLETE_ID = 1002
    const val ACTION_PAUSE = "com.js.anipull.PAUSE"
    const val ACTION_RESUME = "com.js.anipull.RESUME"
    const val ACTION_CANCEL = "com.js.anipull.CANCEL"

    // 2.0 per-job notifications + grouped summary.
    const val GROUP_KEY = "com.js.anipull.DOWNLOADS"
    const val SUMMARY_ID = 1003
    const val EXTRA_JOB_ID = "com.js.anipull.EXTRA_JOB_ID"

    // 2.0-dev2.5 DEFECT 1/1b: per-job ids used to start at 2000,
    // which INCLUDES the foreground-service id 2001 — a job whose
    // id hashed to 1 clobbered the foreground notification. Jobs
    // now start at 3000 (3000..35767), clear of 2001 and of the
    // legacy 1001/1002/1003 ids. The +1 completion id can still
    // in theory collide with another job's progress id; that
    // pre-existing shape is unchanged.
    // Dedup state for the flicker fix: one notification, updated
    // in place under a stable id, re-posted ONLY when the
    // displayed content actually changed and at most ~once a
    // second (paused/completed bypass the throttle so state
    // changes are never delayed). On Nothing OS a content-changed
    // re-post re-animates, so "same text again" posts are dropped
    // here, not just throttled.
    private val lastJobNotify = mutableMapOf<String, Triple<Long, String, Boolean>>()

    private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

    @Volatile var isPaused = false
    @Volatile var isCancelled = false
    @Volatile var lastTitle = ""
    @Volatile var lastProgress = 0
    @Volatile var lastSpeed = 0.0

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Download progress only. Anipull never sends marketing or tracking pings."
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun actionIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showProgress(context: Context, title: String, progress: Int, speedMbps: Double, paused: Boolean) {
        createChannel(context)
        lastTitle = title; lastProgress = progress; lastSpeed = speedMbps
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(if (paused) "Paused · $progress%" else "Episode 1 · ${"%.1f".format(speedMbps)} MB/s · $progress%")
            .setProgress(100, progress, false)
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
        if (paused) builder.addAction(0, "Resume", actionIntent(context, ACTION_RESUME))
        else builder.addAction(0, "Pause", actionIntent(context, ACTION_PAUSE))
        builder.addAction(0, "Cancel", actionIntent(context, ACTION_CANCEL))
        NotificationManagerCompat.from(context).notify(PROGRESS_ID, builder.build())
    }

    fun showComplete(context: Context, title: String) {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Download complete")
            .setContentText("$title — tap to view in Downloads")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
        NotificationManagerCompat.from(context).notify(COMPLETE_ID, builder.build())
        NotificationManagerCompat.from(context).cancel(PROGRESS_ID)
    }

    fun cancelProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(PROGRESS_ID)
    }

    // --- 2.0 per-job notifications --------------------------------------

    fun notificationIdFor(jobId: String): Int = 3000 + (jobId.hashCode() and 0x7FFF)

    private fun notificationsEnabled(context: Context): Boolean =
        context.getSharedPreferences("anipull_settings", Context.MODE_PRIVATE)
            .getBoolean("notif_enabled", true)

    private fun completionEnabled(context: Context): Boolean =
        context.getSharedPreferences("anipull_settings", Context.MODE_PRIVATE)
            .getBoolean("completion_enabled", true)

    private fun jobActionIntent(context: Context, action: String, jobId: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_JOB_ID, jobId)
        val requestCode = (jobId + action).hashCode()
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // 2.0-dev2.5 DEFECT 1/1b: one notification per job, stable id,
    // re-posted only when the displayed content changes (progress
    // integer, speed text, paused state) and at most ~once a
    // second. progress < 0 is the engine's INDETERMINATE sentinel
    // (total size unknown — bytes are flowing but no % exists):
    // indeterminate bar, bytes + speed, never a fake "0%".
    fun showJobProgress(context: Context, job: DownloadJob) {
        if (!notificationsEnabled(context)) return
        createChannel(context)
        val paused = job.state == JobState.PAUSED
        val title = "${job.animeTitle} — E${job.episodeNumber}"
        val indeterminate = job.progress < 0
        val detail = when {
            paused -> "Paused · ${job.progress.coerceAtLeast(0)}%"
            indeterminate -> if (job.downloadedBytes > 0) "${formatMb(job.downloadedBytes)} · ${"%.1f".format(job.speedMBps)} MB/s" else "Connecting…"
            else -> "${"%.1f".format(job.speedMBps)} MB/s · ${job.progress}%"
        }
        val contentText = job.episodeTitle?.let { "$it · $detail" } ?: detail
        // Dedup + throttle: same displayed content never re-posts;
        // changed content waits ~1s between posts, except a pause/
        // resume flip or 100%, which post immediately.
        val contentKey = "$title|$contentText|$paused"
        val now = System.currentTimeMillis()
        val bypassThrottle = paused || job.progress >= 100
        synchronized(lastJobNotify) {
            val last = lastJobNotify[job.id]
            if (last != null && last.second == contentKey) return
            if (last != null && !bypassThrottle && last.third == paused && now - last.first < 1000) return
            lastJobNotify[job.id] = Triple(now, contentKey, paused)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(100, job.progress.coerceIn(0, 100), indeterminate)
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openAppIntent(context))
        if (paused) builder.addAction(0, "Resume", jobActionIntent(context, ACTION_RESUME, job.id))
        else builder.addAction(0, "Pause", jobActionIntent(context, ACTION_PAUSE, job.id))
        builder.addAction(0, "Cancel", jobActionIntent(context, ACTION_CANCEL, job.id))
        NotificationManagerCompat.from(context).notify(notificationIdFor(job.id), builder.build())
    }

    fun showJobComplete(context: Context, job: DownloadJob) {
        synchronized(lastJobNotify) { lastJobNotify.remove(job.id) }
        NotificationManagerCompat.from(context).cancel(notificationIdFor(job.id))
        if (!completionEnabled(context)) return
        createChannel(context)
        val title = "${job.animeTitle} — E${job.episodeNumber}"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Download complete")
            .setContentText("$title — tap to view in Downloads")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
        NotificationManagerCompat.from(context).notify(notificationIdFor(job.id) + 1, builder.build())
    }

    // 2.0-dev2.5 DEFECT 1: NO-OP now. The foreground-service
    // notification (2001) IS the group's single summary (see
    // buildServiceNotification) — a second summary under
    // SUMMARY_ID would fight it for the bundle and could show as
    // a second shade entry. The signature stays so DownloadEngine
    // notifyJob needs no change; cancelSummary still clears any
    // stale 1003 posted by older builds.
    fun showSummary(context: Context, active: List<DownloadJob>) {
        // Intentionally empty: the service notification is the summary.
    }

    fun cancelSummary(context: Context) {
        NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
    }

    fun cancelJob(context: Context, jobId: String) {
        synchronized(lastJobNotify) { lastJobNotify.remove(jobId) }
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(notificationIdFor(jobId))
        manager.cancel(notificationIdFor(jobId) + 1)
    }

    // Persistent foreground-service notification (NOT gated by
    // notif_enabled — the system requires the service to show one).
    // 2.0-dev2.5 DEFECT 1: this is the GROUP SUMMARY for the
    // download group, so the system bundles it with the per-job
    // notifications into ONE shade entry instead of showing a
    // second "Downloading … E1 · N%" notification alongside them.
    // Its text is STATIC ("Anipull downloads", no title, no %) —
    // per-job progress lives only in the per-job notification.
    fun buildServiceNotification(context: Context, text: String): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Anipull")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    // The service refreshes its foreground text through this; the id
    // matches DownloadService.FOREGROUND_ID.
    fun showServiceNotification(context: Context, text: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.notify(
            DownloadService.FOREGROUND_ID,
            buildServiceNotification(context, text),
        )
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(DownloadNotifications.EXTRA_JOB_ID)
        if (jobId != null) {
            // 2.0 route: a real job, not the preview.
            DownloadEngine.init(context)
            when (intent.action) {
                DownloadNotifications.ACTION_PAUSE -> DownloadEngine.pause(jobId)
                DownloadNotifications.ACTION_RESUME -> DownloadEngine.resume(jobId)
                DownloadNotifications.ACTION_CANCEL -> DownloadEngine.cancel(jobId)
            }
            return
        }
        // Legacy 1.9 preview path — unchanged.
        when (intent.action) {
            DownloadNotifications.ACTION_PAUSE -> {
                DownloadNotifications.isPaused = true
                DownloadNotifications.showProgress(context, DownloadNotifications.lastTitle, DownloadNotifications.lastProgress, DownloadNotifications.lastSpeed, paused = true)
            }
            DownloadNotifications.ACTION_RESUME -> {
                DownloadNotifications.isPaused = false
                DownloadNotifications.showProgress(context, DownloadNotifications.lastTitle, DownloadNotifications.lastProgress, DownloadNotifications.lastSpeed, paused = false)
            }
            DownloadNotifications.ACTION_CANCEL -> {
                DownloadNotifications.isCancelled = true
                DownloadNotifications.cancelProgress(context)
            }
        }
    }
}
