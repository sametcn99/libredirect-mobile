@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.redirect.RedirectActivity
import dev.libredirect.mobile.ui.MainUiState
import dev.libredirect.mobile.ui.ServiceUiState
import dev.libredirect.mobile.ui.browser.BrowserTab
import dev.libredirect.mobile.ui.browser.PrivacyStats
import dev.libredirect.mobile.ui.services.ServiceCard

private const val TOP_SERVICES_LIMIT = 5

// Mirrors MainViewModel.PAUSE_INDEFINITE: any pause stamp this far in the
// future means "until I turn it back on".
private const val PAUSE_INDEFINITE_SENTINEL = Long.MAX_VALUE / 4
private const val PAUSE_MINUTES_SHORT = 15
private const val PAUSE_MINUTES_HOUR = 60

// Screen-level composition root: its branches only route to extracted
// section composables, so the raw branch count stays here by design.
@Suppress("CyclomaticComplexMethod")
@Composable
fun HomeScreen(
    state: MainUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var linkText by rememberSaveable { mutableStateOf("") }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }
    val emptyLinkErrorMessage = stringResource(R.string.home_quick_link_error)

    // Clipboard link offer: checked once per Home appearance; MainViewModel
    // dedupes repeated offers and skips everything when the setting is off.
    LaunchedEffect(Unit) {
        actions.onCheckClipboard()
    }

    fun normalizeLink(raw: String): String =
        when {
            raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("http://", ignoreCase = true) -> raw

            raw.isNotEmpty() -> "https://$raw"
            else -> ""
        }

    fun openInSystemBrowser() {
        val normalized = normalizeLink(linkText.trim())
        if (normalized.isEmpty()) {
            linkError = emptyLinkErrorMessage
            return
        }
        linkError = null
        context.startActivity(
            Intent(context, RedirectActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(normalized)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    fun openInAppBrowser() {
        val normalized = normalizeLink(linkText.trim())
        if (normalized.isEmpty()) {
            linkError = emptyLinkErrorMessage
            return
        }
        linkError = null
        actions.onOpenInAppBrowser(normalized)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = state.appVersionDisplay,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = actions.onRefreshClick, enabled = !state.refreshInProgress) {
                        if (state.refreshInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.action_refresh_data),
                            )
                        }
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            homeStatusCards(
                state = state,
                actions = actions,
                openUrl = { context.openExternalUrl(it) },
            )

            item {
                StatusHeroCard(
                    routingEnabled = state.routingEnabled,
                    pausedUntilEpochMillis = state.pausedUntilEpochMillis,
                    activeServicesCount = state.services.count { it.enabled },
                    exceptionsCount = state.exceptions.size,
                    onRoutingToggle = actions.onRoutingEnabledChange,
                    onPauseRouting = actions.onPauseRouting,
                    onResumeRouting = actions.onResumeRouting,
                )
            }

            item {
                QuickLinkCard(
                    linkText = linkText,
                    linkError = linkError,
                    onLinkTextChange = {
                        linkText = it
                        linkError = null
                    },
                    onOpenInApp = ::openInAppBrowser,
                    onOpenExternal = ::openInSystemBrowser,
                )
            }

            // 3.4: local privacy counters.
            item {
                PrivacyStatsCard(
                    stats = state.privacyStats,
                    onReset = actions.onResetPrivacyStats,
                )
            }

            item {
                Text(
                    text = stringResource(R.string.home_quick_actions_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                QuickActionsRow(
                    onOpenBrowser = { actions.onOpenInAppBrowser(null) },
                    onOpenServices = actions.onNavigateToServices,
                    onOpenExceptions = actions.onExceptionsClick,
                    onOpenSettings = actions.onNavigateToSettings,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_popular_services_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.home_see_all_services, state.services.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = actions.onNavigateToServices),
                    )
                }
            }

            val topServices = ServiceSorter.sort(state.services, ServiceSort.POPULARITY).take(TOP_SERVICES_LIMIT)
            items(topServices, key = ServiceUiState::routeId) { service ->
                ServiceCard(
                    service = service,
                    onClick = { actions.onServiceClick(service.routeId) },
                    onEnabledChange = { enabled -> actions.onServiceEnabledChange(service.routeId, enabled) },
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * The three optional cards above the hero card (update, manifest error,
 * clipboard offer) so [HomeScreen] stays under the complexity budget.
 */
private fun LazyListScope.homeStatusCards(
    state: MainUiState,
    actions: HomeActions,
    openUrl: (String) -> Unit,
) {
    if (state.updateAvailable != null) {
        val release = state.updateAvailable
        item {
            UpdateCard(
                versionName = release.versionName,
                onClick = { openUrl(release.releaseUrl) },
            )
        }
    }
    if (state.manifestErrorMessage != null) {
        item {
            ManifestErrorCard(
                error = state.manifestErrorMessage,
                onRetry = actions.onRefreshClick,
            )
        }
    }
    state.clipboardLinkOffer?.let { offerUrl ->
        item {
            ClipboardOfferCard(
                url = offerUrl,
                onRoute = {
                    actions.onDismissClipboardOffer()
                    actions.onOpenInAppBrowser(offerUrl)
                },
                onDismiss = actions.onDismissClipboardOffer,
            )
        }
    }
}

@Composable
private fun StatusHeroCard(
    routingEnabled: Boolean,
    pausedUntilEpochMillis: Long,
    activeServicesCount: Int,
    exceptionsCount: Int,
    onRoutingToggle: (Boolean) -> Unit,
    onPauseRouting: (Int?) -> Unit,
    onResumeRouting: () -> Unit,
) {
    // Opaque on purpose: a 50%-alpha container over the elevated card's own
    // shadowed backdrop composited into a darker edge ring + lighter inner
    // area, reading as a second card nested inside this one (worst in light
    // mode). An opaque color renders as one flat surface.
    val isPaused = pausedUntilEpochMillis > System.currentTimeMillis()
    val containerColor =
        when {
            isPaused -> MaterialTheme.colorScheme.tertiaryContainer
            routingEnabled -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isPaused -> MaterialTheme.colorScheme.tertiary
                                        routingEnabled -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outline
                                    },
                                ),
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Column {
                        val (statusRes, descText) =
                            when {
                                isPaused -> {
                                    val until =
                                        if (pausedUntilEpochMillis >= PAUSE_INDEFINITE_SENTINEL) {
                                            stringResource(R.string.home_pause_until_resume)
                                        } else {
                                            android.text.format.DateFormat
                                                .getTimeFormat(LocalContext.current)
                                                .format(java.util.Date(pausedUntilEpochMillis))
                                        }
                                    R.string.home_routing_paused to
                                        stringResource(R.string.home_routing_paused_desc_fmt, until)
                                }
                                routingEnabled ->
                                    R.string.home_routing_active to
                                        stringResource(R.string.home_routing_active_desc)
                                else ->
                                    R.string.home_routing_disabled to
                                        stringResource(R.string.home_routing_disabled_desc)
                            }
                        Text(
                            text = stringResource(statusRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = descText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = routingEnabled,
                    onCheckedChange = onRoutingToggle,
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }

            // 2.6: temporary pause row — visible while routing is on.
            if (routingEnabled) {
                if (isPaused) {
                    TextButton(onClick = onResumeRouting) {
                        Text(stringResource(R.string.home_resume_routing))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onPauseRouting(PAUSE_MINUTES_SHORT) }) {
                            Text(stringResource(R.string.home_pause_15m), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { onPauseRouting(PAUSE_MINUTES_HOUR) }) {
                            Text(stringResource(R.string.home_pause_1h), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { onPauseRouting(null) }) {
                            Text(
                                stringResource(R.string.home_pause_until_resume),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusStatItem(
                    label = stringResource(R.string.home_stat_active_services),
                    value = "$activeServicesCount",
                )
                StatusStatItem(
                    label = stringResource(R.string.home_stat_exceptions),
                    value = "$exceptionsCount",
                )
                StatusStatItem(
                    label = stringResource(R.string.home_stat_browser),
                    value = stringResource(R.string.home_stat_browser_value),
                )
            }
        }
    }
}

@Composable
private fun StatusStatItem(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickLinkCard(
    linkText: String,
    linkError: String?,
    onLinkTextChange: (String) -> Unit,
    onOpenInApp: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.home_quick_link_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            OutlinedTextField(
                value = linkText,
                onValueChange = onLinkTextChange,
                placeholder = { Text(stringResource(R.string.home_quick_link_placeholder)) },
                singleLine = true,
                isError = linkError != null,
                shape = RoundedCornerShape(14.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        unfocusedBorderColor = Color.Transparent,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(visible = linkError != null) {
                linkError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenInApp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_quick_link_open_in_app),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = onOpenExternal,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.home_quick_link_open_external),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

        }
    }
}

/** Local privacy counters (3.4): what routing did on this device, resettable. */
@Composable
private fun PrivacyStatsCard(
    stats: PrivacyStats,
    onReset: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.home_privacy_stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { confirmReset = true }) {
                    Text(stringResource(R.string.home_privacy_stats_reset))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                StatusStatItem(stringResource(R.string.home_privacy_stat_redirects), stats.totalRedirects.toString())
                StatusStatItem(
                    stringResource(R.string.home_privacy_stat_stripped),
                    stats.strippedTrackingParams.toString(),
                )
                StatusStatItem(stringResource(R.string.home_privacy_stat_unwrapped), stats.unwrappedWrappers.toString())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                StatusStatItem(
                    stringResource(R.string.home_privacy_stat_short_links),
                    stats.resolvedShortLinks.toString(),
                )
                StatusStatItem(stringResource(R.string.home_privacy_stat_blocked), stats.blockedRequests.toString())
                Spacer(Modifier.weight(1f))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.home_privacy_stats_reset)) },
            text = { Text(stringResource(R.string.home_privacy_stats_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onReset()
                    },
                ) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun QuickActionsRow(
    onOpenBrowser: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenExceptions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickActionItem(
            icon = Icons.Default.Public,
            label = stringResource(R.string.nav_browser),
            onClick = onOpenBrowser,
            modifier = Modifier.weight(1f),
        )
        QuickActionItem(
            icon = Icons.Default.Tune,
            label = stringResource(R.string.nav_services),
            onClick = onOpenServices,
            modifier = Modifier.weight(1f),
        )
        QuickActionItem(
            icon = Icons.Default.Block,
            label = stringResource(R.string.home_stat_exceptions),
            onClick = onOpenExceptions,
            modifier = Modifier.weight(1f),
        )
        QuickActionItem(
            icon = Icons.Default.Settings,
            label = stringResource(R.string.nav_settings),
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun UpdateCard(
    versionName: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Update, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_update_available_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.home_update_available_desc, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ClipboardOfferCard(
    url: String,
    onRoute: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ContentPaste,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_clipboard_offer_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = BrowserTab.hostOf(url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRoute) {
                Text(stringResource(R.string.home_clipboard_offer_route))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ManifestErrorCard(
    error: String,
    onRetry: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_manifest_error_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

private fun Context.openExternalUrl(url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
    } catch (_: Exception) {
    }
}
