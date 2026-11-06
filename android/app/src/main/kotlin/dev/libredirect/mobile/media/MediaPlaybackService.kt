package dev.libredirect.mobile.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.libredirect.mobile.MainActivity
import dev.libredirect.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service keeping routed media playback alive while the app is
 * backgrounded (4.6). It owns no player — the WebView does — and exists to
 * (a) hold a foreground notification so the process is not killed with the
 * activity off-screen, and (b) expose play/pause/stop actions that travel
 * over [MediaPlaybackBus] to the WebView bridge. Started only when the user
 * enabled background audio and media is actually playing.
 */
class MediaPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        scope.launch {
            MediaPlaybackBus.state.collectLatest { state ->
                if (!state.isPlaying) {
                    stopSelf()
                } else {
                    notify(buildNotification(state))
                }
            }
        }
        scope.launch {
            MediaPlaybackBus.commands.collectLatest { command ->
                if (command == MediaPlaybackBus.Command.STOP) {
                    MediaPlaybackBus.reportPlaying(false)
                    stopSelf()
                }
                // PLAY_PAUSE is handled by the browser layer (JS into the page);
                // the notification state updates through the bus.
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int =
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                MediaPlaybackBus.send(MediaPlaybackBus.Command.PLAY_PAUSE)
                START_STICKY
            }
            ACTION_STOP -> {
                MediaPlaybackBus.send(MediaPlaybackBus.Command.STOP)
                stopSelf()
                START_NOT_STICKY
            }
            else -> START_STICKY
        }

    override fun onDestroy() {
        scope.cancel()
        MediaPlaybackBus.reportPlaying(false)
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification(MediaPlaybackBus.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: MediaPlaybackBus.MediaState): Notification {
        val channelManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            channelManager?.getNotificationChannel(CHANNEL_ID) == null
        ) {
            channelManager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.media_playback_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val playPauseIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                2,
                Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val title = state.pageTitle.ifBlank { getString(R.string.media_playback_title) }
        val subtitle = state.host.ifBlank { getString(R.string.media_playback_subtitle) }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_routing)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.media_playback_play_pause), playPauseIntent)
            .addAction(0, getString(R.string.media_playback_stop), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "dev.libredirect.mobile.media.PLAY_PAUSE"
        const val ACTION_STOP = "dev.libredirect.mobile.media.STOP"
        private const val CHANNEL_ID = "media_playback"
        private const val NOTIFICATION_ID = 41

        /** Starts the service from app code; no-ops below API 26 needs (all supported levels ok). */
        fun start(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaPlaybackService::class.java))
        }
    }
}
