@file:OptIn(ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.settings.BackupOptions
import dev.libredirect.mobile.settings.CustomTrackingParam
import dev.libredirect.mobile.settings.SettingsBackupCodec
import dev.libredirect.mobile.settings.TabAutoClose
import dev.libredirect.mobile.ui.MainUiState
import java.text.DateFormat
import java.util.Date

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
    query: String = "",
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val context = LocalContext.current

        fun matches(vararg labels: Int): Boolean =
            query.isBlank() || labels.any { context.getString(it).contains(query.trim(), ignoreCase = true) }
        if (matches(
                R.string.settings_section_privacy,
                R.string.settings_unwrap_wrappers,
                R.string.settings_short_links,
                R.string.settings_strip_query_params,
                R.string.host_link_rules,
                R.string.settings_content_blocking,
                R.string.settings_auto_failover,
                R.string.routing_preset_title,
            )
        ) {
            SectionTitle(stringResource(R.string.settings_section_privacy))

            SettingsToggleItem(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.settings_unwrap_wrappers),
                subtitle = stringResource(R.string.settings_unwrap_wrappers_desc),
                checked = state.unwrapWrappersEnabled,
                onCheckedChange = actions.onUnwrapWrappersChange,
            )

            SettingsToggleItem(
                icon = Icons.AutoMirrored.Filled.ShortText,
                title = stringResource(R.string.settings_short_links),
                subtitle = stringResource(R.string.settings_short_links_desc),
                checked = state.shortLinkResolveEnabled,
                onCheckedChange = actions.onShortLinkResolveChange,
            )

            SettingsToggleItem(
                icon = Icons.Default.CleaningServices,
                title = stringResource(R.string.settings_strip_query_params),
                subtitle = stringResource(R.string.settings_strip_query_params_desc),
                checked = state.queryParamStripEnabled,
                onCheckedChange = actions.onQueryParamStripChange,
            )

            RoutingPresetPicker(actions.onRoutingPreset)
            TrackingParamsCard(state = state, onChange = actions.onCustomTrackingParamsChange)
            HostLinkPolicyEditor(state.linkPolicies.hosts, actions.onHostLinkPolicyChange)

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

            LatencyMeasureCard(state = state, actions = actions)
        }
        if (matches(
                R.string.settings_group_browser,
                R.string.settings_history_limit,
                R.string.settings_auto_close_tabs,
                R.string.settings_background_audio,
                R.string.settings_auto_hide_toolbar,
                R.string.settings_close_incognito,
            )
        ) {
            SectionTitle(stringResource(R.string.settings_group_browser))
            BrowserExtrasCards(state = state, actions = actions)
        }
        if (matches(
                R.string.settings_section_downloads,
                R.string.settings_ask_before_download,
                R.string.settings_download_wifi_only,
            )
        ) {
            DownloadSettingsCards(state = state, actions = actions)
        }
        if (matches(R.string.settings_group_data, R.string.settings_backup, R.string.backup_rules_only)) {
            SectionTitle(stringResource(R.string.settings_group_data))
            BackupCard(state = state, actions = actions)
        }
        if (matches(R.string.settings_section_app_info, R.string.diagnostics_title)) {
            WhatsNewCard(state = state, actions = actions)
            DiagnosticsEntryCard(actions = actions)
        }
    }
}

@Composable
private fun RoutingPresetPicker(onSelect: (dev.libredirect.mobile.settings.RoutingPreset) -> Unit) {
    var candidate by remember { mutableStateOf<dev.libredirect.mobile.settings.RoutingPreset?>(null) }
    val choices =
        mapOf(
            dev.libredirect.mobile.settings.RoutingPreset.BALANCED to R.string.routing_preset_balanced,
            dev.libredirect.mobile.settings.RoutingPreset.STRICT to R.string.routing_preset_strict,
            dev.libredirect.mobile.settings.RoutingPreset.CLEAN_ONLY to R.string.routing_preset_clean,
        )
    Column {
        Text(stringResource(R.string.routing_preset_title), style = MaterialTheme.typography.titleSmall)
        choices.forEach { (preset, label) ->
            TextButton(onClick = { candidate = preset }) { Text(stringResource(label)) }
        }
    }
    candidate?.let { preset ->
        val description =
            when (preset) {
                dev.libredirect.mobile.settings.RoutingPreset.BALANCED -> R.string.routing_preset_balanced_description
                dev.libredirect.mobile.settings.RoutingPreset.STRICT -> R.string.routing_preset_strict_description
                dev.libredirect.mobile.settings.RoutingPreset.CLEAN_ONLY -> R.string.routing_preset_clean_description
            }
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text(stringResource(choices.getValue(preset))) },
            text = { Text(stringResource(description)) },
            confirmButton = {
                TextButton(onClick = {
                    onSelect(preset)
                    candidate = null
                }) {
                    Text(stringResource(R.string.backup_import_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { candidate = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 3.2: user-defined tracking parameter rules, managed in a dialog. */
@Composable
private fun TrackingParamsCard(
    state: MainUiState,
    onChange: (List<CustomTrackingParam>) -> Unit,
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

/** 2.3: manual and automatic instance latency measurement card. */
@Composable
private fun LatencyMeasureCard(
    state: MainUiState,
    actions: SettingsActions,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_background_latency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.settings_background_latency_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.backgroundLatencyMeasureEnabled,
                    onCheckedChange = actions.onBackgroundLatencyChange,
                )
            }

            val statusText =
                when {
                    state.isMeasuringAllServices -> stringResource(R.string.settings_latency_measuring)
                    state.latencyMeasureMessage != null -> state.latencyMeasureMessage
                    state.lastLatencyMeasureAt > 0L -> {
                        val formatted =
                            DateFormat.getDateTimeInstance(
                                DateFormat.SHORT,
                                DateFormat.SHORT,
                            ).format(Date(state.lastLatencyMeasureAt))
                        stringResource(
                            R.string.settings_latency_last_measured_fmt,
                            formatted,
                            state.lastLatencyMeasureCount,
                        )
                    }
                    else -> stringResource(R.string.settings_latency_never_measured)
                }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.lastLatencyMeasureAt > 0L && !state.isMeasuringAllServices) {
                    TextButton(onClick = actions.onClearLatencyMeasurements) {
                        Text(stringResource(R.string.settings_latency_clear))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = actions.onMeasureAllServices,
                    enabled = !state.isMeasuringAllServices,
                ) {
                    if (state.isMeasuringAllServices) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_latency_measuring_btn))
                    } else {
                        Text(stringResource(R.string.settings_latency_measure_now))
                    }
                }
            }
        }
    }
}

/** 4.5/4.6 browser behavior cards: history limit, tab auto-close, toolbars, audio, incognito. */
@Suppress("MagicNumber")
private val historyLimitOptions = listOf(0, 50, 200, 500, 1000)

@Composable
private fun BrowserExtrasCards(
    state: MainUiState,
    actions: SettingsActions,
) {
    SettingsChoice(
        title = stringResource(R.string.settings_history_limit),
        choices =
            (historyLimitOptions + state.historyLimit).distinct().sorted().associateWith { limit ->
                if (limit == 0) {
                    stringResource(
                        R.string.history_limit_off,
                    )
                } else {
                    stringResource(R.string.history_limit_fmt, limit)
                }
            },
        selected = state.historyLimit,
        onSelect = actions.onHistoryLimitChange,
    )
    SettingsChoice(
        title = stringResource(R.string.settings_auto_close_tabs),
        choices =
            mapOf(
                TabAutoClose.NEVER to stringResource(R.string.tab_auto_close_never),
                TabAutoClose.ONE_DAY to stringResource(R.string.tab_auto_close_1d),
                TabAutoClose.ONE_WEEK to stringResource(R.string.tab_auto_close_1w),
                TabAutoClose.ONE_MONTH to stringResource(R.string.tab_auto_close_1m),
            ),
        selected = state.autoCloseTabs,
        onSelect = actions.onAutoCloseTabsChange,
    )

    Text(
        stringResource(R.string.private_download_policy),
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** In-app download manager preferences. */
@Composable
private fun DownloadSettingsCards(
    state: MainUiState,
    actions: SettingsActions,
) {
    SectionTitle(stringResource(R.string.settings_section_downloads))

    SettingsToggleItem(
        icon = Icons.Default.Download,
        title = stringResource(R.string.settings_ask_before_download),
        subtitle = stringResource(R.string.settings_ask_before_download_desc),
        checked = state.askBeforeDownload,
        onCheckedChange = actions.onAskBeforeDownloadChange,
    )

    SettingsToggleItem(
        icon = Icons.Default.Wifi,
        title = stringResource(R.string.settings_download_wifi_only),
        subtitle = stringResource(R.string.settings_download_wifi_only_desc),
        checked = state.downloadWifiOnly,
        onCheckedChange = actions.onDownloadWifiOnlyChange,
    )

    SettingsToggleItem(
        icon = Icons.Default.PrivacyTip,
        title = stringResource(R.string.settings_clear_incognito_downloads),
        subtitle = stringResource(R.string.settings_clear_incognito_downloads_desc),
        checked = state.clearIncognitoDownloads,
        onCheckedChange = actions.onClearIncognitoDownloadsChange,
    )
}

/** 5.1: SAF export/import of the single settings backup file. */
@Composable
private fun BackupCard(
    state: MainUiState,
    actions: SettingsActions,
) {
    var exportOptions by remember { mutableStateOf(BackupOptions()) }
    var showExport by remember { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { actions.onExportSettings(it, exportOptions) }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { actions.onImportSettings(it) }
        }
    ElevatedCardItem(
        icon = Icons.Default.Save,
        title = stringResource(R.string.settings_backup),
        subtitle = stringResource(R.string.settings_backup_desc_fmt),
        onClick = { showExport = true },
        trailingContent = {
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                Text(stringResource(R.string.settings_backup_import))
            }
        },
    )
    if (showExport) {
        BackupExportDialog(state, onDismiss = { showExport = false }) { options ->
            exportOptions = options
            showExport = false
            val fileName =
                if (options.serviceIds == null) "libredirect-mobile-settings.json" else "libredirect-rules.json"
            exportLauncher.launch(fileName)
        }
    }
    state.pendingSettingsImport?.let { backup ->
        BackupImportDialog(backup, state, actions)
    }
}

@Composable
private fun BackupExportDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
    onExport: (BackupOptions) -> Unit,
) {
    var options by remember { mutableStateOf(BackupOptions()) }
    val selected = options.serviceIds
    val isRules = selected != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_contents)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BackupCheckbox(stringResource(R.string.backup_rules_only), isRules) { enabled ->
                    options =
                        if (enabled) {
                            BackupOptions(false, true, false, emptySet())
                        } else {
                            BackupOptions()
                        }
                }
                if (selected == null) {
                    BackupCheckbox(
                        stringResource(R.string.settings_title),
                        options.settings,
                    ) { options = options.copy(settings = it) }
                    BackupCheckbox(stringResource(R.string.custom_services_title), options.customRoutes) {
                        options = options.copy(customRoutes = it)
                    }
                    BackupCheckbox(stringResource(R.string.backup_item_bookmarks), options.bookmarks) {
                        options = options.copy(bookmarks = it)
                    }
                } else {
                    state.services.sortedBy { it.name }.forEach { service ->
                        BackupCheckbox(service.name, service.routeId in selected) { checked ->
                            val ids = if (checked) selected + service.routeId else selected - service.routeId
                            options = options.copy(serviceIds = ids)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected?.isNotEmpty() ?: (options.settings || options.customRoutes || options.bookmarks),
                onClick = { onExport(options) },
            ) { Text(stringResource(R.string.backup_export_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BackupCheckbox(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun BackupImportDialog(
    backup: SettingsBackupCodec.Backup,
    state: MainUiState,
    actions: SettingsActions,
) {
    var replace by remember(backup) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions.onDismissImport,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        R.string.backup_import_summary_fmt,
                        backup.appVersionName,
                        backup.customRoutes.size,
                        backup.settings.exceptions.size,
                    ),
                )
                if (backup.includesSettings) Text(stringResource(R.string.backup_replace_settings))
                if (backup.bookmarks.isNotEmpty()) {
                    Text(stringResource(R.string.backup_import_include_bookmarks_fmt, backup.bookmarks.size))
                }
                val shared = backup.sharedRules
                if (shared != null) {
                    val instanceRouteIds = shared.instanceSelections.keys.map { it.substringBefore('/') }
                    val ids = shared.frontends.keys + shared.policies.keys + instanceRouteIds
                    ids.sorted().forEach { id ->
                        val name = state.services.find { it.routeId == id }?.name ?: id
                        Text(name)
                        val target = shared.policies[id]?.targetPackage
                        if (target != null) Text(target, style = MaterialTheme.typography.bodySmall)
                        shared.instanceSelections.filterKeys { it.substringBefore('/') == id }.values.forEach {
                            Text(it.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.pendingRuleItems.forEach { item ->
                    Text(item.route.name, style = MaterialTheme.typography.labelLarge)
                    Text(item.route.hosts.joinToString(", "))
                    item.route.frontends.flatMap { it.instances }.forEach { Text(it) }
                    item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (item.conflict) Text(stringResource(R.string.backup_import_conflict))
                }
                if (shared != null || state.pendingRuleItems.any { it.conflict }) {
                    BackupCheckbox(stringResource(R.string.backup_replace_conflicts), replace) { replace = it }
                }
            }
        },
        confirmButton = {
            Button(onClick = { actions.onApplyImport(replace) }) { Text(stringResource(R.string.backup_import_apply)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onDismissImport,
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
    onChange: (List<CustomTrackingParam>) -> Unit,
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
                                CustomTrackingParam(
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
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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

@Composable
internal fun <T> SettingsChoice(
    title: String,
    choices: Map<T, String>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(choices[selected].orEmpty()) },
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    onSelect(value)
                })
            }
        }
    }
}
