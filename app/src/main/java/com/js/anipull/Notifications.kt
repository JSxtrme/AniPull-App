package com.js.anipull

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
object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    const val PROGRESS_ID = 1001
    const val COMPLETE_ID = 1002
    const val ACTION_PAUSE = "com.js.anipull.PAUSE"
    const val ACTION_RESUME = "com.js.anipull.RESUME"
    const val ACTION_CANCEL = "com.js.anipull.CANCEL"

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
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
