@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.settings.CustomTrackingParam
import dev.libredirect.mobile.settings.TabAutoClose
import dev.libredirect.mobile.ui.MainUiState
import dev.libredirect.mobile.ui.browser.SearchEngine
import dev.libredirect.mobile.ui.browser.SearchEngineDialog

private const val GITHUB_REPO_URL = "https://github.com/sametcn99/libredirect-mobile"

data class SettingsActions(
    val onBrowserClick: () -> Unit,
    val onExceptionsClick: () -> Unit,
    val onCustomServicesClick: () -> Unit,
    val onRefreshManifest: () -> Unit,
    val onSearchEngineSelect: (SearchEngine) -> Unit = {},
    val onSaveCustomSearchEngine: (name: String, template: String) -> Unit = { _, _ -> },
    val onCheckForUpdates: () -> Unit = {},
    val onClipboardOfferChange: (Boolean) -> Unit = {},
    val onAutoInstanceRefreshChange: (Boolean) -> Unit = {},
    val onAppLockChange: (Boolean) -> Unit = {},
    // 2.x/3.x/4.x/5.x batch (see SettingsExtrasSection)
    val onUnwrapWrappersChange: (Boolean) -> Unit = {},
    val onShortLinkResolveChange: (Boolean) -> Unit = {},
    val onCustomTrackingParamsChange: (List<CustomTrackingParam>) -> Unit = {},
    val onContentBlockerChange: (Boolean) -> Unit = {},
    val onUpdateBlocklist: () -> Unit = {},
    val onAutoFailoverChange: (Boolean) -> Unit = {},
    val onBackgroundLatencyChange: (Boolean) -> Unit = {},
    val onBackgroundAudioChange: (Boolean) -> Unit = {},
    val onCloseIncognitoChange: (Boolean) -> Unit = {},
    val onAutoCloseTabsChange: (TabAutoClose) -> Unit = {},
    val onAutoHideToolbarChange: (Boolean) -> Unit = {},
    val onExportSettings: (Uri) -> Unit = {},
    val onImportSettings: (Uri) -> Unit = {},
    val onApplyImport: () -> Unit = {},
    val onDismissImport: () -> Unit = {},
    val onOpenRelease: (String) -> Unit = {},
    val onOpenDiagnostics: () -> Unit = {},
    val onOpenOnboarding: () -> Unit = {},
)

@Suppress("CyclomaticComplexMethod")
@Composable
fun SettingsScreen(
    state: MainUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isSearchEngineDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isLanguageDialogVisible by rememberSaveable { mutableStateOf(false) }
    val currentLanguageTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            item {
                SectionTitle(stringResource(R.string.settings_section_browser_routing))
            }

            item {
                val isInApp = state.selectedBrowserPackage == BrowserLauncher.IN_APP_BROWSER_PACKAGE
                val badge = if (isInApp) stringResource(R.string.settings_badge_in_app) else null
                SettingsCardItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_default_browser),
                    subtitle = state.selectedBrowserLabel,
                    badge = badge,
                    onClick = actions.onBrowserClick,
                )
            }

            item {
                // C1: system "default browser" role (API 29+). Only offered
                // where the role exists; the request itself is launched by
                // the host activity's result launcher (see BrowserRoleHost).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
                    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                        val isRoleHeld = roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
                        SettingsCardItem(
                            icon = Icons.Default.Public,
                            title = stringResource(R.string.settings_set_default_browser),
                            subtitle =
                                if (isRoleHeld) {
                                    stringResource(R.string.settings_default_browser_held)
                                } else {
                                    stringResource(R.string.settings_default_browser_desc)
                                },
                            onClick = { (context as? BrowserRoleHost)?.requestBrowserRole() },
                        )
                    }
                }
            }

            item {
                SettingsCardItem(
                    icon = Icons.Default.Translate,
                    title = stringResource(R.string.settings_language),
                    subtitle =
                        when {
                            currentLanguageTag.isBlank() -> stringResource(R.string.settings_language_system_default)
                            currentLanguageTag.startsWith("tr", ignoreCase = true) -> "Türkçe"
                            else -> "English"
                        },
                    onClick = { isLanguageDialogVisible = true },
                )
            }

            item {
                SettingsCardItem(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.settings_search_engine),
                    subtitle =
                        SearchEngine.resolve(
                            selectedId = state.searchEngine,
                            customName = state.customSearchEngineName,
                            customUrl = state.customSearchEngineUrl,
                        ).displayName,
                    onClick = { isSearchEngineDialogVisible = true },
                )
            }

            item {
                SettingsCardItem(
                    icon = Icons.Default.Block,
                    title = stringResource(R.string.settings_exceptions),
                    subtitle = stringResource(R.string.settings_exceptions_subtitle_fmt, state.exceptions.size),
                    onClick = actions.onExceptionsClick,
                )
            }

            item {
                SettingsCardItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.settings_custom_services),
                    subtitle = stringResource(R.string.settings_custom_services_subtitle_fmt, state.customRoutes.size),
                    onClick = actions.onCustomServicesClick,
                )
            }

            item {
                val isAppLockAvailable =
                    remember {
                        androidx.biometric.BiometricManager
                            .from(context)
                            .canAuthenticate(
                                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                            ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                    }
                if (isAppLockAvailable) {
                    SettingsToggleItem(
                        icon = Icons.Default.Lock,
                        title = stringResource(R.string.settings_app_lock),
                        subtitle = stringResource(R.string.settings_app_lock_desc),
                        checked = state.appLockEnabled,
                        onCheckedChange = actions.onAppLockChange,
                    )
                }
            }

            item {
                SettingsToggleItem(
                    icon = Icons.Default.ContentPaste,
                    title = stringResource(R.string.settings_clipboard_offer),
                    subtitle = stringResource(R.string.settings_clipboard_offer_desc),
                    checked = state.clipboardOfferEnabled,
                    onCheckedChange = actions.onClipboardOfferChange,
                )
            }

            item {
                SectionTitle(stringResource(R.string.settings_section_routing_db))
            }

            item {
                ManifestDatabaseCard(
                    revision = state.manifestRevision,
                    isRefreshing = state.refreshInProgress,
                    refreshMessage = state.lastRefreshMessage,
                    errorMessage = state.manifestErrorMessage,
                    generationNotes = state.generationNotes,
                    onRefresh = actions.onRefreshManifest,
                )
            }

            item {
                SettingsToggleItem(
                    icon = Icons.Default.Autorenew,
                    title = stringResource(R.string.settings_auto_instance_refresh),
                    subtitle = stringResource(R.string.settings_auto_instance_refresh_desc),
                    checked = state.autoInstanceRefreshEnabled,
                    onCheckedChange = actions.onAutoInstanceRefreshChange,
                )
            }

            // 2.x–5.x additions: privacy, browser extras, backup, diagnostics.
            item {
                SettingsExtrasSection(state = state, actions = actions)
            }

            item {
                SectionTitle(stringResource(R.string.settings_section_app_info))
            }

            item {
                AppVersionCard(
                    versionDisplay = state.appVersionDisplay,
                    updateAvailable = state.updateAvailable?.versionName,
                    isChecking = state.updateCheckInProgress,
                    hasChecked = state.updateChecked,
                    hasFailed = state.updateCheckFailed,
                    onCheckForUpdates = actions.onCheckForUpdates,
                    onOpenRelease = {
                        state.updateAvailable?.releaseUrl?.let { url ->
                            context.openUrl(url)
                        }
                    },
                    onOpenSourceCode = {
                        context.openUrl(GITHUB_REPO_URL)
                    },
                )
            }

            // 5.2: re-run the first-launch guide on demand.
            item {
                SettingsCardItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_onboarding),
                    subtitle = stringResource(R.string.onboarding_welcome_desc),
                    onClick = actions.onOpenOnboarding,
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (isLanguageDialogVisible) {
        LanguageDialog(
            currentTag = currentLanguageTag,
            onSelect = { tag ->
                isLanguageDialogVisible = false
                val locales =
                    if (tag == null) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(tag)
                    }
                AppCompatDelegate.setApplicationLocales(locales)
            },
            onDismiss = { isLanguageDialogVisible = false },
        )
    }

    if (isSearchEngineDialogVisible) {
        SearchEngineDialog(
            currentEngine =
                SearchEngine.resolve(
                    selectedId = state.searchEngine,
                    customName = state.customSearchEngineName,
                    customUrl = state.customSearchEngineUrl,
                ),
            customEngine = SearchEngine.customOrNull(state.customSearchEngineName, state.customSearchEngineUrl),
            onSelectEngine = { engine ->
                actions.onSearchEngineSelect(engine)
                isSearchEngineDialogVisible = false
            },
            onSaveCustom = { name, template ->
                actions.onSaveCustomSearchEngine(name, template)
                isSearchEngineDialogVisible = false
            },
            onDismiss = { isSearchEngineDialogVisible = false },
        )
    }
}

@Composable
private fun LanguageDialog(
    currentTag: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options =
        listOf(
            null to stringResource(R.string.settings_language_system_default),
            "en" to "English",
            "tr" to "Türkçe",
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { (tag, label) ->
                    val isSelected =
                        if (tag == null) {
                            currentTag.isBlank()
                        } else {
                            currentTag.startsWith(tag, ignoreCase = true)
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(tag) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelect(tag) },
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
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
internal fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
internal fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsCardItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (badge != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ManifestDatabaseCard(
    revision: Int?,
    isRefreshing: Boolean,
    refreshMessage: String?,
    errorMessage: String?,
    generationNotes: List<String>,
    onRefresh: () -> Unit,
) {
    var areNotesExpanded by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.settings_routing_db_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.settings_db_revision_fmt, revision?.toString() ?: "—"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (refreshMessage != null) {
                Text(
                    text = refreshMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_db_up_to_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A1: what the generator dropped from live upstream data (unsupported
            // target regexes, instanceless frontends), so a service the user
            // expects to be redirectable explains itself instead of vanishing.
            if (generationNotes.isNotEmpty()) {
                TextButton(
                    onClick = { areNotesExpanded = !areNotesExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (areNotesExpanded) {
                                    R.string.settings_db_notes_hide
                                } else {
                                    R.string.settings_db_notes_show_fmt
                                },
                                generationNotes.size,
                            ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (areNotesExpanded) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        generationNotes.forEach { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onRefresh,
                enabled = !isRefreshing,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_db_updating))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_db_update_now))
                }
            }
        }
    }
}

@Composable
private fun AppVersionCard(
    versionDisplay: String,
    updateAvailable: String?,
    isChecking: Boolean,
    hasChecked: Boolean,
    hasFailed: Boolean,
    onCheckForUpdates: () -> Unit,
    onOpenRelease: () -> Unit,
    onOpenSourceCode: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = versionDisplay,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            when {
                isChecking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.settings_checking_updates),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                updateAvailable != null -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRelease),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Update,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.settings_update_available_fmt, updateAvailable),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                hasFailed -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onCheckForUpdates),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = stringResource(R.string.settings_check_updates_failed),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                hasChecked -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_app_up_to_date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = onCheckForUpdates,
                enabled = !isChecking,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_check_updates))
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenSourceCode)
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.settings_github_source),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun Context.openUrl(url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
    } catch (_: Exception) {
    }
}
