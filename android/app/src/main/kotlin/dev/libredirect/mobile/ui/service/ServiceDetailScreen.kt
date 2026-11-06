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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.url.UrlValidation
import dev.libredirect.mobile.ui.FrontendUiState
import dev.libredirect.mobile.ui.ServiceUiState
import kotlinx.coroutines.delay

private const val CUSTOM_COMMIT_DEBOUNCE_MILLIS = 600L

@Composable
fun ServiceDetailScreen(
    service: ServiceUiState,
    onBack: () -> Unit,
    onFrontendSelected: (String) -> Unit,
    onInstanceSelectionChange: (frontendId: String, selection: InstanceSelection) -> Unit,
    redirectCount: Int = 0,
    onPinShortcut: () -> Unit = {},
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
