@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.redirect.RedirectActivity
import dev.libredirect.mobile.ui.MainUiState
import dev.libredirect.mobile.ui.ServiceUiState

@Composable
fun HomeScreen(
    state: MainUiState,
    onRoutingEnabledChange: (Boolean) -> Unit,
    onBrowserClick: () -> Unit,
    onServiceClick: (String) -> Unit,
    onServiceEnabledChange: (String, Boolean) -> Unit,
    onExceptionsClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onCustomServicesClick: () -> Unit,
) {
    val context = LocalContext.current
    var linkText by rememberSaveable { mutableStateOf("") }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }

    fun openLink() {
        val rawLink = linkText.trim()
        val normalizedLink =
            when {
                rawLink.startsWith("https://", ignoreCase = true) ||
                    rawLink.startsWith("http://", ignoreCase = true) -> rawLink
                rawLink.isNotEmpty() -> "https://$rawLink"
                else -> ""
            }
        if (normalizedLink.isEmpty()) {
            linkError = "Enter a URL first"
            return
        }

        linkError = null
        context.startActivity(
            Intent(context, RedirectActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(normalizedLink)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("LibRedirect Mobile") }) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth()) {
            state.updateAvailable?.let { update ->
                item {
                    UpdateAvailableRow(
                        versionName = update.versionName,
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                        },
                    )
                    HorizontalDivider()
                }
            }

            item {
                LinkOpenRow(
                    value = linkText,
                    error = linkError,
                    onValueChange = {
                        linkText = it
                        linkError = null
                    },
                    onOpen = ::openLink,
                )
                HorizontalDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text("Routing") },
                    supportingContent = { Text(if (state.routingEnabled) "Enabled" else "Disabled") },
                    trailingContent = {
                        Switch(checked = state.routingEnabled, onCheckedChange = onRoutingEnabledChange)
                    },
                )
                HorizontalDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text("Default browser") },
                    supportingContent = { Text(state.selectedBrowserLabel) },
                    modifier = Modifier.clickable(onClick = onBrowserClick),
                )
                HorizontalDivider()
            }

            item {
                Text(
                    text = "Services",
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }

            items(state.services, key = ServiceUiState::routeId) { service ->
                ServiceRow(
                    service = service,
                    onClick = { onServiceClick(service.routeId) },
                    onEnabledChange = { enabled -> onServiceEnabledChange(service.routeId, enabled) },
                )
            }

            item {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Exceptions") },
                    supportingContent = { Text("${state.exceptions.size} configured") },
                    modifier = Modifier.clickable(onClick = onExceptionsClick),
                )
            }

            item {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Custom services") },
                    supportingContent = { Text("${state.customRoutes.size} configured") },
                    modifier = Modifier.clickable(onClick = onCustomServicesClick),
                )
            }

            item {
                HorizontalDivider()
                RoutingDataRow(state = state, onRefreshClick = onRefreshClick)
            }

            item {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Source code") },
                    supportingContent = { Text("GitHub · sametcn99/libredirect-mobile") },
                    modifier =
                        Modifier.clickable {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/sametcn99/libredirect-mobile"),
                                ),
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailableRow(
    versionName: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text("Update available") },
        supportingContent = { Text("Version $versionName - tap to view release") },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun LinkOpenRow(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Open a link")
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Paste or enter a URL") },
            placeholder = { Text("https://www.instagram.com/...") },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open")
        }
        error?.let { Text(it) }
    }
}

@Composable
private fun ServiceRow(
    service: ServiceUiState,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val selectedFrontendName =
        service.frontends.find { it.id == service.selectedFrontendId }?.name ?: service.selectedFrontendId
    ListItem(
        headlineContent = { Text(service.name) },
        supportingContent = { Text(selectedFrontendName) },
        trailingContent = { Switch(checked = service.enabled, onCheckedChange = onEnabledChange) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun RoutingDataRow(
    state: MainUiState,
    onRefreshClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Routing data")
        Text("Revision ${state.manifestRevision ?: "—"}")
        state.lastRefreshMessage?.let { Text(it) }
        if (state.refreshInProgress) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
        } else {
            TextButton(onClick = onRefreshClick, contentPadding = PaddingValues(0.dp)) {
                Text("Update now")
            }
        }
    }
}
