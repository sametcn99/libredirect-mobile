@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.service

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.core.routing.InstanceHealth
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.url.UrlValidation
import dev.libredirect.mobile.settings.RouteMode
import dev.libredirect.mobile.ui.FrontendUiState
import dev.libredirect.mobile.ui.ServiceUiState
import kotlinx.coroutines.delay

private const val CUSTOM_COMMIT_DEBOUNCE_MILLIS = 600L
private const val FAST_THRESHOLD_MS = 500L

@Composable
fun ServiceDetailScreen(
    service: ServiceUiState,
    onBack: () -> Unit,
    onFrontendSelected: (String) -> Unit,
    onInstanceSelectionChange: (frontendId: String, selection: InstanceSelection) -> Unit,
    redirectCount: Int = 0,
    onPinShortcut: () -> Unit = {},
    routeMode: RouteMode = RouteMode.ALWAYS,
    onRouteModeChange: (RouteMode) -> Unit = {},
    instanceHealth: Map<String, InstanceHealth> = emptyMap(),
    isMeasuringHealth: Boolean = false,
    onMeasureHealth: () -> Unit = {},
    nowMillis: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(service.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            item {
                ServiceSummaryRow(
                    redirectCount = redirectCount,
                    onPinShortcut = onPinShortcut,
                )
            }
            item {
                // 2.4: three-state per-service routing mode.
                RouteModeSelector(
                    mode = routeMode,
                    onChange = onRouteModeChange,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.service_detail_frontend_header),
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(service.frontends) { frontend ->
                FrontendRow(
                    frontend = frontend,
                    selected = frontend.id == service.selectedFrontendId,
                    onSelect = { onFrontendSelected(frontend.id) },
                )
                if (frontend.id == service.selectedFrontendId && frontend.needsInstance) {
                    InstanceSelector(
                        frontend = frontend,
                        onSelectionChange = { selection -> onInstanceSelectionChange(frontend.id, selection) },
                    )
                    // 2.3: instance health panel for the selected frontend.
                    InstanceHealthPanel(
                        instances = frontend.instances,
                        health = instanceHealth,
                        isMeasuring = isMeasuringHealth,
                        onMeasureAll = onMeasureHealth,
                        nowMillis = nowMillis,
                    )
                }
            }
        }
    }
}

/** Local usage stats (A6) plus a pinned-shortcut action (C3) for this service. */
@Composable
private fun ServiceSummaryRow(
    redirectCount: Int,
    onPinShortcut: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (redirectCount > 0) {
            Text(
                text = stringResource(R.string.service_detail_redirect_count_fmt, redirectCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.width(1.dp))
        }
        TextButton(onClick = onPinShortcut) {
            Icon(
                Icons.Default.AddToHomeScreen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.service_detail_pin_shortcut))
        }
    }
}

/** Always / Ask every time / Off — one segmented row (2.4). */
@Composable
private fun RouteModeSelector(
    mode: RouteMode,
    onChange: (RouteMode) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.service_mode_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf(RouteMode.ALWAYS, RouteMode.ASK, RouteMode.OFF)
            modes.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = mode == candidate,
                    onClick = { onChange(candidate) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(
                        text =
                            stringResource(
                                when (candidate) {
                                    RouteMode.ALWAYS -> R.string.service_mode_always
                                    RouteMode.ASK -> R.string.service_mode_ask
                                    RouteMode.OFF -> R.string.service_mode_off
                                },
                            ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 2.3: all instances of the selected frontend with status badges + "measure all". */
@Composable
private fun InstanceHealthPanel(
    instances: List<String>,
    health: Map<String, InstanceHealth>,
    isMeasuring: Boolean,
    onMeasureAll: () -> Unit,
    nowMillis: () -> Long,
) {
    if (instances.isEmpty()) return
    Column(modifier = Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.service_health_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (isMeasuring) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.service_health_measuring),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                TextButton(onClick = onMeasureAll) {
                    Text(stringResource(R.string.service_health_measure_all))
                }
            }
        }
        instances.forEach { instance ->
            val status = healthStatus(health[instance], nowMillis())
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color =
                        when (status) {
                            HealthStatus.FAST -> MaterialTheme.colorScheme.primaryContainer
                            HealthStatus.SLOW -> MaterialTheme.colorScheme.secondaryContainer
                            HealthStatus.UNREACHABLE, HealthStatus.COOLDOWN -> MaterialTheme.colorScheme.errorContainer
                            HealthStatus.UNMEASURED -> MaterialTheme.colorScheme.surfaceVariant
                        },
                ) {
                    Text(
                        text = stringResource(statusLabelRes(status)),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = instance.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                health[instance]?.latencyMillis?.takeIf { it > 0 }?.let { latency ->
                    Text(
                        text = stringResource(R.string.browser_instance_latency_fmt, latency),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private enum class HealthStatus { FAST, SLOW, UNREACHABLE, UNMEASURED, COOLDOWN }

private fun healthStatus(
    health: InstanceHealth?,
    nowMillis: Long,
): HealthStatus {
    val latency = health?.latencyMillis
    return when {
        health?.isCoolingDown(nowMillis) == true -> HealthStatus.COOLDOWN
        latency != null && latency < 0 -> HealthStatus.UNREACHABLE
        latency == null -> HealthStatus.UNMEASURED
        latency <= FAST_THRESHOLD_MS -> HealthStatus.FAST
        else -> HealthStatus.SLOW
    }
}

private fun statusLabelRes(status: HealthStatus): Int =
    when (status) {
        HealthStatus.FAST -> R.string.health_status_fast
        HealthStatus.SLOW -> R.string.health_status_slow
        HealthStatus.UNREACHABLE -> R.string.health_status_unreachable
        HealthStatus.UNMEASURED -> R.string.health_status_unmeasured
        HealthStatus.COOLDOWN -> R.string.health_status_cooldown
    }

@Composable
private fun FrontendRow(
    frontend: FrontendUiState,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(frontend.name) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.clickable(onClick = onSelect),
    )
    HorizontalDivider()
}

@Composable
private fun InstanceSelector(
    frontend: FrontendUiState,
    onSelectionChange: (InstanceSelection) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        var customText by remember(frontend.id) {
            mutableStateOf((frontend.selection as? InstanceSelection.Custom)?.instance.orEmpty())
        }
        var showCustomError by remember(frontend.id) { mutableStateOf(false) }
        var customTouched by remember(frontend.id) { mutableStateOf(false) }

        fun commitCustom() {
            val trimmed = customText.trim().lowercase()
            if (UrlValidation.isValidHttpsOrigin(trimmed)) {
                showCustomError = false
                onSelectionChange(InstanceSelection.Custom(trimmed))
            } else {
                showCustomError = true
            }
        }

        InstanceModeRow(
            label = stringResource(R.string.service_detail_instance_auto),
            selected = frontend.selection is InstanceSelection.Automatic,
            onClick = {
                customTouched = false
                onSelectionChange(InstanceSelection.Automatic)
            },
        )
        // 2.1: fastest-measured mode.
        InstanceModeRow(
            label = stringResource(R.string.service_detail_instance_fastest),
            selected = frontend.selection is InstanceSelection.Fastest,
            onClick = {
                customTouched = false
                onSelectionChange(InstanceSelection.Fastest)
            },
        )
        for (instance in frontend.instances) {
            InstanceModeRow(
                label = instance,
                selected = (frontend.selection as? InstanceSelection.Pinned)?.instance == instance,
                onClick = {
                    customTouched = false
                    onSelectionChange(InstanceSelection.Pinned(instance))
                },
            )
        }

        // Debounced commit while typing: only valid HTTPS origins reach the
        // settings store, and only after a short typing pause. Clicking
        // Automatic or a pinned instance above cancels the pending commit.
        LaunchedEffect(frontend.id, customText, customTouched) {
            val trimmed = customText.trim().lowercase()
            if (customTouched && UrlValidation.isValidHttpsOrigin(trimmed)) {
                showCustomError = false
                delay(CUSTOM_COMMIT_DEBOUNCE_MILLIS)
                if (customTouched) {
                    onSelectionChange(InstanceSelection.Custom(trimmed))
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = frontend.selection is InstanceSelection.Custom,
                onClick = { commitCustom() },
            )
            OutlinedTextField(
                value = customText,
                onValueChange = {
                    customText = it
                    customTouched = true
                },
                label = { Text(stringResource(R.string.service_detail_instance_custom)) },
                isError = showCustomError,
                supportingText = {
                    if (showCustomError) {
                        Text(stringResource(R.string.service_detail_instance_invalid))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InstanceModeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
