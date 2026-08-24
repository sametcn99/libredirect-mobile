@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import dev.libredirect.mobile.update.UpdateInfo

private data class HomeContentInput(
    val linkText: String,
    val linkError: String?,
    val onLinkChange: (String) -> Unit,
    val onOpenLink: () -> Unit,
    val contentPadding: androidx.compose.foundation.layout.PaddingValues,
    val context: Context,
)

@Composable
fun HomeScreen(
    state: MainUiState,
    actions: HomeActions,
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
        HomeContent(
            state = state,
            actions = actions,
            input =
                HomeContentInput(
                    linkText = linkText,
                    linkError = linkError,
                    onLinkChange = {
                        linkText = it
                        linkError = null
                    },
                    onOpenLink = ::openLink,
                    contentPadding = padding,
                    context = context,
                ),
        )
    }
}

@Composable
private fun HomeContent(
    state: MainUiState,
    actions: HomeActions,
    input: HomeContentInput,
) {
    LazyColumn(
        modifier = Modifier.padding(input.contentPadding).fillMaxWidth(),
    ) {
        addUpdateItem(state.updateAvailable, input.context::openRelease)
        addManifestErrorItem(state.manifestErrorMessage, actions.onRefreshClick)
        addLinkItem(input.linkText, input.linkError, input.onLinkChange, input.onOpenLink)
        addRoutingItem(state, actions.onRoutingEnabledChange)
        addBrowserItem(state, actions.onBrowserClick)
        addServiceItems(state, actions)
        addNavigationItems(state, actions)
        addRoutingDataItem(state, actions.onRefreshClick)
        addSourceCodeItem(input.context)
    }
}

private fun LazyListScope.addManifestErrorItem(
    error: String?,
    onRetry: () -> Unit,
) {
    error ?: return
    item {
        ListItem(
            headlineContent = { Text("Routing data unavailable") },
            supportingContent = { Text(error) },
            trailingContent = {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.errorContainer),
        )
        HorizontalDivider()
    }
}

private fun LazyListScope.addUpdateItem(
    update: UpdateInfo?,
    onClick: (String) -> Unit,
) {
    update ?: return
    item {
        UpdateAvailableRow(versionName = update.versionName, onClick = { onClick(update.releaseUrl) })
        HorizontalDivider()
    }
}

private fun LazyListScope.addLinkItem(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    item {
        LinkOpenRow(value = value, error = error, onValueChange = onValueChange, onOpen = onOpen)
        HorizontalDivider()
    }
}

private fun LazyListScope.addRoutingItem(
    state: MainUiState,
    onRoutingEnabledChange: (Boolean) -> Unit,
) {
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
}

private fun LazyListScope.addBrowserItem(
    state: MainUiState,
    onBrowserClick: () -> Unit,
) {
    item {
        ListItem(
            headlineContent = { Text("Default browser") },
            supportingContent = { Text(state.selectedBrowserLabel) },
            modifier = Modifier.clickable(onClick = onBrowserClick),
        )
        HorizontalDivider()
    }
}

private fun LazyListScope.addServiceItems(
    state: MainUiState,
    actions: HomeActions,
) {
    item {
        Text(
            text = "Services",
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(state.services, key = ServiceUiState::routeId) { service ->
        ServiceRow(
            service = service,
            onClick = { actions.onServiceClick(service.routeId) },
            onEnabledChange = { enabled -> actions.onServiceEnabledChange(service.routeId, enabled) },
        )
    }
}

private fun LazyListScope.addNavigationItems(
    state: MainUiState,
    actions: HomeActions,
) {
    item {
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Exceptions") },
            supportingContent = { Text("${state.exceptions.size} configured") },
            modifier = Modifier.clickable(onClick = actions.onExceptionsClick),
        )
    }
    item {
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Custom services") },
            supportingContent = { Text("${state.customRoutes.size} configured") },
            modifier = Modifier.clickable(onClick = actions.onCustomServicesClick),
        )
    }
}

private fun LazyListScope.addSourceCodeItem(context: Context) {
    item {
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Source code") },
            supportingContent = { Text("GitHub · sametcn99/libredirect-mobile") },
            modifier = Modifier.clickable { context.openRelease(SOURCE_CODE_URL) },
        )
    }
}

private fun LazyListScope.addRoutingDataItem(
    state: MainUiState,
    onRefreshClick: () -> Unit,
) {
    item {
        HorizontalDivider()
        RoutingDataRow(state = state, onRefreshClick = onRefreshClick)
    }
}

private fun android.content.Context.openRelease(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private const val SOURCE_CODE_URL = "https://github.com/sametcn99/libredirect-mobile"

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
