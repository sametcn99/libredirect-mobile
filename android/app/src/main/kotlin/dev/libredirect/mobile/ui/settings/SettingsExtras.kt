@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.settings.TabAutoClose
import dev.libredirect.mobile.ui.MainUiState

/**
 * The settings additions that came with the 2.x–5.x feature batch, kept in
 * one section composable so [SettingsScreen] stays under its complexity
 * budget: privacy/link-cleaning, browser extras, backup/restore and the
 * diagnostics entry.
 */
@Composable
fun SettingsExtrasSection(
    state: MainUiState,
    actions: SettingsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle(stringResource(R.string.settings_section_privacy))

        SettingsToggleItem(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.settings_unwrap_wrappers),
            subtitle = stringResource(R.string.settings_unwrap_wrappers_desc),
            checked = state.unwrapWrappersEnabled,
            onCheckedChange = actions.onUnwrapWrappersChange,
        )

        SettingsToggleItem(
            icon = Icons.Default.ShortText,
            title = stringResource(R.string.settings_short_links),
            subtitle = stringResource(R.string.settings_short_links_desc),
            checked = state.shortLinkResolveEnabled,
            onCheckedChange = actions.onShortLinkResolveChange,
        )

        TrackingParamsCard(state = state, onChange = actions.onCustomTrackingParamsChange)

        SettingsToggleItem(
            icon = Icons.Default.Block,
            title = stringResource(R.string.settings_content_blocking),
            subtitle = stringResource(R.string.settings_content_blocking_desc),
            checked = state.contentBlockerEnabled,
            onCheckedChange = actions.onContentBlockerChange,
        )
        if (state.contentBlockerEnabled) {
            BlocklistCard(state = state, actions = actions)
        }

        SettingsToggleItem(
            icon = Icons.Default.Autorenew,
            title = stringResource(R.string.settings_auto_failover),
            subtitle = stringResource(R.string.settings_auto_failover_desc),
            checked = state.autoFailoverEnabled,
            onCheckedChange = actions.onAutoFailoverChange,
        )

        SettingsToggleItem(
            icon = Icons.Default.GraphicEq,
            title = stringResource(R.string.settings_background_latency),
            subtitle = stringResource(R.string.settings_background_latency_desc),
            checked = state.backgroundLatencyMeasureEnabled,
            onCheckedChange = actions.onBackgroundLatencyChange,
        )

        BrowserExtrasCards(state = state, actions = actions)
        BackupCard(state = state, actions = actions)
        WhatsNewCard(state = state, actions = actions)
        DiagnosticsEntryCard(actions = actions)
    }
}

/** 3.2: user-defined tracking parameter rules, managed in a dialog. */
@Composable
private fun TrackingParamsCard(
    state: MainUiState,
    onChange: (List<dev.libredirect.mobile.settings.CustomTrackingParam>) -> Unit,
) {
    var isVisible by rememberSaveable { mutableStateOf(false) }
    ElevatedCardItem(
        icon = Icons.Default.PrivacyTip,
        title = stringResource(R.string.settings_custom_tracking),
        subtitle = stringResource(R.string.settings_custom_tracking_desc_fmt, state.customTrackingParams.size),
        onClick = { isVisible = true },
    )
    if (isVisible) {
        TrackingParamsDialog(
            state = state,
            onChange = onChange,
            onDismiss = { isVisible = false },
        )
    }
}

/** 4.2: blocklist status + manual refresh. */
@Composable
private fun BlocklistCard(
    state: MainUiState,
    actions: SettingsActions,
) {
    ElevatedCardItem(
        icon = Icons.Default.Save,
        title = stringResource(R.string.settings_blocker_update_now),
        subtitle =
            if (state.refreshInProgress) {
                stringResource(R.string.settings_db_updating)
            } else {
                state.lastRefreshMessage ?: stringResource(R.string.settings_blocker_update_now)
            },
        onClick = { if (!state.refreshInProgress) actions.onUpdateBlocklist() },
    )
}

/** 4.5/4.6 browser behavior cards: history limit, tab auto-close, toolbars, audio, incognito. */
@Composable
private fun BrowserExtrasCards(
    state: MainUiState,
    actions: SettingsActions,
) {
    SettingsToggleItem(
        icon = Icons.Default.Timer,
        title = stringResource(R.string.settings_auto_close_tabs),
        subtitle = stringResource(R.string.settings_auto_close_tabs_desc),
        checked = state.autoCloseTabs != TabAutoClose.NEVER,
        onCheckedChange = { enabled ->
            actions.onAutoCloseTabsChange(if (enabled) TabAutoClose.ONE_WEEK else TabAutoClose.NEVER)
        },
    )

    SettingsToggleItem(
        icon = Icons.Default.MusicNote,
        title = stringResource(R.string.settings_background_audio),
        subtitle = stringResource(R.string.settings_background_audio_desc),
        checked = state.backgroundAudioEnabled,
        onCheckedChange = { enabled -> actions.onBackgroundAudioChange(enabled) },
    )

    SettingsToggleItem(
        icon = Icons.Default.Visibility,
        title = stringResource(R.string.settings_auto_hide_toolbar),
        subtitle = stringResource(R.string.settings_auto_hide_toolbar_desc),
        checked = state.autoHideToolbar,
        onCheckedChange = actions.onAutoHideToolbarChange,
    )

    SettingsToggleItem(
        icon = Icons.Default.Shield,
        title = stringResource(R.string.settings_close_incognito),
        subtitle = stringResource(R.string.settings_close_incognito_desc),
        checked = state.closeIncognitoOnExit,
        onCheckedChange = actions.onCloseIncognitoChange,
    )
}

/** 5.1: SAF export/import of the single settings backup file. */
@Composable
private fun BackupCard(
    state: MainUiState,
    actions: SettingsActions,
) {
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { actions.onExportSettings(it) }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { actions.onImportSettings(it) }
        }

    ElevatedCardItem(
        icon = Icons.Default.Save,
        title = stringResource(R.string.settings_backup),
        subtitle = stringResource(R.string.settings_backup_desc_fmt),
        onClick = { exportLauncher.launch("libredirect-mobile-settings.json") },
        trailingContent = {
            TextButton(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
            ) {
                Text(stringResource(R.string.settings_backup_import))
            }
        },
    )
    state.pendingSettingsImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { actions.onDismissImport() },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = {
                Column {
                    Text(
                        text =
                            stringResource(
                                R.string.backup_import_summary_fmt,
                                backup.appVersionName,
                                backup.customRoutes.size,
                                backup.settings.exceptions.size,
                            ),
                    )
                    if (backup.bookmarks.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.backup_import_include_bookmarks_fmt, backup.bookmarks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { actions.onApplyImport() }) {
                    Text(stringResource(R.string.backup_import_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.onDismissImport() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 5.4: in-app release notes when an update is available. */
@Composable
private fun WhatsNewCard(
    state: MainUiState,
    actions: SettingsActions,
) {
    state.updateAvailable?.let { update ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        ElevatedCardItem(
            icon = Icons.Default.Autorenew,
            title = stringResource(R.string.settings_whats_new_title, update.versionName),
            subtitle = update.releaseNotes.firstOrNull() ?: update.releaseUrl,
            onClick = { showDialog = true },
        )
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.settings_whats_new_title, update.versionName)) },
                text = {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (update.releaseNotes.isEmpty()) {
                            Text(update.releaseUrl)
                        } else {
                            update.releaseNotes.forEach { line -> Text("• $line") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { actions.onOpenRelease(update.releaseUrl) }) {
                        Text(stringResource(R.string.update_open_release))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                },
            )
        }
    }
}

@Composable
private fun DiagnosticsEntryCard(actions: SettingsActions) {
    ElevatedCardItem(
        icon = Icons.Default.GraphicEq,
        title = stringResource(R.string.settings_diagnostics),
        subtitle = stringResource(R.string.settings_diagnostics_desc),
        onClick = actions.onOpenDiagnostics,
    )
}

/** 3.2 dialog: add/remove user-defined tracking parameter rules. */
@Composable
private fun TrackingParamsDialog(
    state: MainUiState,
    onChange: (List<dev.libredirect.mobile.settings.CustomTrackingParam>) -> Unit,
    onDismiss: () -> Unit,
) {
    var rules by rememberSaveable(state.customTrackingParams) {
        mutableStateOf(state.customTrackingParams)
    }
    var hostInput by rememberSaveable { mutableStateOf("") }
    var nameInput by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracking_params_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.tracking_params_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text(stringResource(R.string.tracking_params_hint_host)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.tracking_params_hint_name)) },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        if (nameInput.isNotBlank()) {
                            rules =
                                rules +
                                dev.libredirect.mobile.settings.CustomTrackingParam(
                                    host = hostInput.trim().ifBlank { null },
                                    name = nameInput.trim(),
                                )
                            hostInput = ""
                            nameInput = ""
                        }
                    },
                    enabled = nameInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_add))
                }
                Column {
                    rules.forEach { rule ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = rule.host?.let { host -> "$host → ${rule.name}" } ?: "• ${rule.name}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { rules = rules - rule }) {
                                Text(stringResource(R.string.action_remove))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onChange(rules.toList())
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ElevatedCardItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    androidx.compose.material3.ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailingContent?.invoke()
        }
    }
}
