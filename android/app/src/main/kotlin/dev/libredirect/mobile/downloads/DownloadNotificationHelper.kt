package dev.libredirect.mobile.downloads

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.libredirect.mobile.MainActivity
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.ShortcutActions

private const val CHANNEL_PROGRESS = "downloads_progress"
private const val CHANNEL_COMPLETE = "downloads_complete"
private const val REQUEST_OPEN_DOWNLOADS = 4510
private const val REQUEST_PAUSE_ALL = 4511
private const val REQUEST_CANCEL_ALL = 4512
private const val COMPLETE_NOTIFICATION_BASE = 4600
private const val COMPLETE_NOTIFICATION_MASK = 0x0FFF
private const val PERCENT_MAX = 100

class DownloadNotificationHelper(private val context: Context) {
    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val mgr = manager ?: return
        if (mgr.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    context.getString(R.string.downloads_channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_COMPLETE) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_COMPLETE,
                    context.getString(R.string.downloads_channel_complete),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    fun buildProgressNotification(activeItems: List<DownloadItem>): Notification {
        ensureChannels()
        val running = activeItems.filter { it.status == DownloadStatus.RUNNING }
        val primary = running.firstOrNull() ?: activeItems.firstOrNull()
        val count = activeItems.size

        val title =
            when {
                primary == null -> context.getString(R.string.downloads_title)
                count == 1 -> primary.fileName
                else -> context.getString(R.string.downloads_notif_active_fmt, count)
            }
        val (progress, indeterminate) = computeAggregateProgress(activeItems, primary)
        val subtitle = buildProgressSubtitle(primary, count, running.sumOf { it.speedBytesPerSec })

        return NotificationCompat
            .Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setProgress(PERCENT_MAX, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloadsPendingIntent())
            .addAction(
                0,
                context.getString(R.string.downloads_action_pause),
                serviceActionIntent(DownloadService.ACTION_PAUSE_ALL, REQUEST_PAUSE_ALL),
            )
            .addAction(
                0,
                context.getString(R.string.action_cancel),
                serviceActionIntent(DownloadService.ACTION_CANCEL_ALL, REQUEST_CANCEL_ALL),
            )
            .build()
    }

    private fun computeAggregateProgress(
        activeItems: List<DownloadItem>,
        primary: DownloadItem?,
    ): Pair<Int, Boolean> {
        if (activeItems.size == 1 && primary != null) {
            val fraction = primary.progressFraction
            return if (fraction != null) (fraction * PERCENT_MAX).toInt() to false else 0 to true
        }
        val knownTotals = activeItems.filter { it.totalBytes > 0L }
        val allTotalsKnown = knownTotals.isNotEmpty() && knownTotals.size == activeItems.size
        return if (allTotalsKnown) {
            val sumDone = knownTotals.sumOf { it.bytesDownloaded }
            val sumTotal = knownTotals.sumOf { it.totalBytes }.coerceAtLeast(1L)
            val pct = ((sumDone.toDouble() / sumTotal.toDouble()) * PERCENT_MAX).toInt().coerceIn(0, PERCENT_MAX)
            pct to false
        } else {
            0 to true
        }
    }

    private fun buildProgressSubtitle(
        primary: DownloadItem?,
        count: Int,
        speedSum: Long,
    ): String =
        buildString {
            when {
                primary != null && count == 1 && primary.totalBytes > 0L -> {
                    append(formatByteSize(primary.bytesDownloaded))
                    append(" / ")
                    append(formatByteSize(primary.totalBytes))
                }
                primary != null && count == 1 && primary.bytesDownloaded > 0L ->
                    append(formatByteSize(primary.bytesDownloaded))
                else -> append(context.getString(R.string.downloads_status_running))
            }
            if (speedSum > 0L) {
                append(" • ")
                append(formatByteSize(speedSum))
                append("/s")
            }
        }

    fun postCompletionNotification(item: DownloadItem) {
        if (!canPostNotifications()) return
        ensureChannels()
        val isSuccess = item.status == DownloadStatus.COMPLETED
        val title =
            if (isSuccess) {
                context.getString(R.string.downloads_notif_complete_title)
            } else {
                context.getString(R.string.downloads_notif_failed_title)
            }
        val icon =
            if (isSuccess) {
                android.R.drawable.stat_sys_download_done
            } else {
                android.R.drawable.stat_notify_error
            }
        val body =
            if (isSuccess && item.bytesDownloaded > 0L) {
                "${item.fileName} • ${formatByteSize(item.bytesDownloaded)}"
            } else {
                item.fileName
            }
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_COMPLETE)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(openDownloadsPendingIntent())
                .build()
        val notifId = COMPLETE_NOTIFICATION_BASE + (item.id.hashCode() and COMPLETE_NOTIFICATION_MASK)
        manager?.notify(notifId, notification)
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openDownloadsPendingIntent(): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = ShortcutActions.OPEN_DOWNLOADS
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_DOWNLOADS,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceActionIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
