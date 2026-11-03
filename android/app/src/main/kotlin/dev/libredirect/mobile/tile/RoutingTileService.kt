package dev.libredirect.mobile.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.libredirect.mobile.R
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile mirroring the home screen's master routing toggle.
 * Reads and writes [SettingsRepository]'s `routing_enabled` preference; the
 * home screen stays in sync because it collects the same DataStore flow.
 */
class RoutingTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val newState = tile.state != Tile.STATE_ACTIVE
        tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
        tileScope.launch {
            settingsRepository.setRoutingEnabled(newState)
        }
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        tileScope.launch {
            val enabled = settingsRepository.settings.first().routingEnabled
            withContext(Dispatchers.Main) {
                updateTile(enabled)
            }
        }
    }

    private fun updateTile(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Tile.setSubtitle exists only from API 29; the label alone is fine below that.
            tile.subtitle = getString(R.string.tile_routing_subtitle)
        }
        tile.updateTile()
    }
}
