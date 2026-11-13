package dev.libredirect.mobile.downloads

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.libredirect.mobile.LibRedirectApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service keeping active in-app downloads alive while the app is
 * backgrounded, exposing Pause/Cancel actions on the system notification.
 * Runs only while at least one download is RUNNING or QUEUED.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationHelper: DownloadNotificationHelper

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = DownloadNotificationHelper(this)
        val repository = (application as LibRedirectApplication).downloadRepository
        val initialActive = repository.downloads.value.filter { it.status.isActive }
        startInForeground(initialActive)

        scope.launch {
            repository.downloads.collectLatest { items ->
                val active = items.filter { it.status.isActive }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(PROGRESS_NOTIFICATION_ID, notificationHelper.buildProgressNotification(active))
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val repository = (application as LibRedirectApplication).downloadRepository
        val handled =
            when (intent?.action) {
                ACTION_PAUSE_ALL -> {
                    repository.pauseAll()
                    true
                }
                ACTION_CANCEL_ALL -> {
                    repository.cancelAllActive()
                    true
                }
                else -> false
            }
        if (handled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(activeItems: List<DownloadItem>) {
        val notification = notificationHelper.buildProgressNotification(activeItems)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "dev.libredirect.mobile.downloads.PAUSE_ALL"
        const val ACTION_CANCEL_ALL = "dev.libredirect.mobile.downloads.CANCEL_ALL"
        private const val PROGRESS_NOTIFICATION_ID = 4501

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: IllegalStateException) {
                // Background FGS restriction: transfer still continues in applicationScope while process lives.
            }
        }
    }
}
