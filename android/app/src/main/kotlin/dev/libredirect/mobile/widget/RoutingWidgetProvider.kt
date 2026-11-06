package dev.libredirect.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.libredirect.mobile.R
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.redirect.RedirectActivity
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home-screen widget (5.3): the master routing switch plus a one-tap
 * "route the clipboard link" action. No Glance dependency — plain
 * RemoteViews; every click refreshes the widget from the settings flow so
 * state can never drift.
 */
class RoutingWidgetProvider : AppWidgetProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        scope.launch {
            val settings = SettingsRepository(context).settings.first()
            val paused = settings.pausedUntilEpochMillis > System.currentTimeMillis()
            val label =
                when {
                    paused -> context.getString(R.string.widget_paused)
                    settings.routingEnabled -> context.getString(R.string.widget_routing_on)
                    else -> context.getString(R.string.widget_routing_off)
                }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val views =
                    android.widget.RemoteViews(context.packageName, R.layout.widget_routing).apply {
                        setTextViewText(R.id.widget_status, label)
                        setOnClickPendingIntent(R.id.widget_toggle, togglePendingIntent(context))
                        setOnClickPendingIntent(R.id.widget_clipboard, clipboardPendingIntent(context))
                    }
                appWidgetManager.updateAppWidget(appWidgetIds, views)
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE ->
                scope.launch {
                    val repository = SettingsRepository(context)
                    val current = repository.settings.first()
                    repository.setRoutingEnabled(!current.routingEnabled)
                    refreshAll(context)
                }
            ACTION_ROUTE_CLIPBOARD ->
                scope.launch {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    val url = UrlCandidate.find(text)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (url == null) {
                            Toast.makeText(context, R.string.widget_clipboard_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            // Reuse the standard share-target entry so the link
                            // goes through the exact same routing pipeline.
                            val send =
                                Intent(context, RedirectActivity::class.java).apply {
                                    action = Intent.ACTION_SEND
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            context.startActivity(send)
                        }
                    }
                }
            else -> Unit
        }
    }

    private fun togglePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RoutingWidgetProvider::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun clipboardPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, RoutingWidgetProvider::class.java).setAction(ACTION_ROUTE_CLIPBOARD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, RoutingWidgetProvider::class.java))
        onUpdate(context, manager, ids)
    }

    override fun onDisabled(context: Context) {
        scope.cancel()
        super.onDisabled(context)
    }

    companion object {
        const val ACTION_TOGGLE = "dev.libredirect.mobile.widget.TOGGLE_ROUTING"
        const val ACTION_ROUTE_CLIPBOARD = "dev.libredirect.mobile.widget.ROUTE_CLIPBOARD"
    }
}
