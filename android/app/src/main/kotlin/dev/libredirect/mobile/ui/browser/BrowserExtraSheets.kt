@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.settings.SiteSetting

private const val ZOOM_DEFAULT = 100
private const val ZOOM_STEP = 25
private const val ZOOM_MIN = 50
private const val ZOOM_MAX = 300
private const val TILE_GRID_COLUMNS = 4
private const val TILE_ICON_SIZE = 48
private const val MAX_START_TILES = 12
private const val MAX_PER_SOURCE = 8

/**
 * Per-site browser overrides (4.3), opened from the address bar's security
 * icon. Unset toggles follow the global setting; changing a value applies
 * to the live tabs of that host immediately.
 */
@Composable
fun SiteSettingsSheet(
    host: String,
    siteSetting: SiteSetting?,
    onToggle: (transform: (SiteSetting) -> SiteSetting) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = siteSetting ?: SiteSetting()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.browser_site_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = stringResource(R.string.browser_site_settings_host_fmt, host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(8.dp))

            SiteToggleRow(
                title = stringResource(R.string.site_settings_javascript),
                subtitle = stringResource(R.string.site_settings_javascript_desc),
                checked = current.javaScriptEnabled ?: true,
                onChange = { value -> onToggle { it.copy(javaScriptEnabled = value) } },
            )
            SiteToggleRow(
                title = stringResource(R.string.site_settings_desktop),
                subtitle = null,
                checked = current.desktopMode ?: false,
                onChange = { value -> onToggle { it.copy(desktopMode = value) } },
            )
            SiteToggleRow(
                title = stringResource(R.string.site_settings_force_dark),
                subtitle = null,
                checked = current.forceDark ?: false,
                onChange = { value -> onToggle { it.copy(forceDark = value) } },
            )
            SiteToggleRow(
                title = stringResource(R.string.site_settings_content_blocking),
                subtitle = null,
                checked = current.contentBlocking ?: true,
                onChange = { value -> onToggle { it.copy(contentBlocking = value) } },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 20.dp))

            // Text size override: stepped +/- control; steps land on the same
            // values the global text-size dialog offers.
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.site_settings_text_size),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.browser_text_zoom_percent_fmt, current.textZoom ?: ZOOM_DEFAULT),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = {
                        onToggle {
                            it.copy(
                                textZoom = ((it.textZoom ?: 100) - ZOOM_STEP).coerceAtLeast(ZOOM_MIN),
                            )
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = stringResource(R.string.action_clear),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        onToggle {
                            it.copy(
                                textZoom = ((it.textZoom ?: 100) + ZOOM_STEP).coerceAtMost(ZOOM_MAX),
                            )
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.action_add),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            TextButton(
                onClick = onReset,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(stringResource(R.string.site_settings_reset))
            }
        }
    }
}

@Composable
private fun SiteToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** In-app view of the system DownloadManager's recent entries (4.5). */
@Composable
fun DownloadsDialog(
    entries: List<DownloadEntry>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_title), fontWeight = FontWeight.Bold) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.downloads_empty))
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    entries.forEach { entry ->
                        Column {
                            Text(
                                text = entry.title.ifBlank { "?" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = statusLabel(entry.status),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun statusLabel(status: Int): String =
    when (status) {
        android.app.DownloadManager.STATUS_RUNNING,
        android.app.DownloadManager.STATUS_PENDING,
        -> stringResource(R.string.downloads_status_running)
        android.app.DownloadManager.STATUS_SUCCESSFUL -> stringResource(R.string.downloads_status_done)
        android.app.DownloadManager.STATUS_FAILED -> stringResource(R.string.downloads_status_failed)
        android.app.DownloadManager.STATUS_PAUSED -> stringResource(R.string.downloads_status_paused)
        else -> stringResource(R.string.downloads_status_running)
    }

/**
 * The new-tab start page (4.5): bookmarks and the most-visited hosts from
 * history, shown as a grid while a fresh tab sits on the engine home page.
 */
@Composable
fun StartPageOverlay(
    bookmarks: List<BrowserBookmark>,
    history: List<BrowserHistoryItem>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    data class Tile(
        val title: String,
        val url: String,
    )

    val tiles =
        remember(bookmarks, history) {
            val bookmarkTiles =
                bookmarks
                    .distinctBy { it.url }
                    .take(MAX_PER_SOURCE)
                    .map { Tile(it.title, it.url) }
            val topHosts =
                history
                    .groupingBy { BrowserTab.hostOf(it.url) }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(MAX_PER_SOURCE)
                    .mapNotNull { (host, _) ->
                        val url = history.firstOrNull { BrowserTab.hostOf(it.url) == host }?.url
                        if (url == null) return@mapNotNull null
                        Tile(host, url)
                    }
            (bookmarkTiles + topHosts).distinctBy { it.url }.take(MAX_START_TILES)
        }

    if (tiles.isEmpty()) {
        Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
            Text(
                text = stringResource(R.string.browser_start_page_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(TILE_GRID_COLUMNS),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(tiles) { tile ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(tile.url) }
                        .padding(4.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(TILE_ICON_SIZE.dp),
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = tile.title.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
