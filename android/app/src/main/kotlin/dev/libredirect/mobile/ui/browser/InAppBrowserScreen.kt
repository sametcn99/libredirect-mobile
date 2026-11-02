@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.libredirect.mobile.R

@Composable
fun InAppBrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val activeTab = state.activeTab
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = state.isTabsSheetVisible || (activeTab?.canGoBack == true)) {
        if (state.isTabsSheetVisible) {
            viewModel.setTabsSheetVisible(false)
        } else if (activeTab?.canGoBack == true) {
            viewModel.goBackActiveTab()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserTopBar(
                tab = activeTab,
                tabCount = state.tabs.size,
                onNavigate = { query ->
                    viewModel.navigateActiveTab(query)
                    focusManager.clearFocus()
                },
                onReload = viewModel::reloadActiveTab,
                onStop = viewModel::stopActiveTab,
                onOpenTabs = { viewModel.setTabsSheetVisible(true) },
                onNewTab = viewModel::openNewTab,
                onShare = {
                    activeTab?.url?.let {
                        context.shareUrl(it, context.getString(R.string.browser_share_link_title))
                    }
                },
                onOpenExternal = { activeTab?.url?.let { context.openExternalUrl(it) } },
                onCloseTab = { activeTab?.let { viewModel.closeTab(it.id) } },
            )

            if (activeTab?.isLoading == true) {
                LinearProgressIndicator(
                    progress = { activeTab.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            }

            AnimatedVisibility(
                visible = state.activeRedirectNotice != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.activeRedirectNotice?.let { notice ->
                    RedirectBanner(
                        notice = notice,
                        onDismiss = viewModel::dismissRedirectNotice,
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                if (activeTab != null) {
                    key(activeTab.id) {
                        AndroidView(
                            factory = { ctx ->
                                viewModel.getOrCreateWebView(activeTab.id, ctx)
                            },
                            update = { webView ->
                                if (webView.parent != null && webView.parent is ViewGroup) {
                                    // Managed by Compose layout
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            BrowserBottomBar(
                canGoBack = activeTab?.canGoBack == true,
                canGoForward = activeTab?.canGoForward == true,
                tabCount = state.tabs.size,
                onBack = { viewModel.goBackActiveTab() },
                onForward = { viewModel.goForwardActiveTab() },
                onHome = { viewModel.navigateActiveTab(BrowserTab.DEFAULT_HOME_URL) },
                onTabs = { viewModel.setTabsSheetVisible(true) },
                onNewTab = viewModel::openNewTab,
            )
        }

        if (state.isTabsSheetVisible) {
            TabSwitcherSheet(
                tabs = state.tabs,
                activeTabId = state.activeTabId,
                actions =
                    TabSwitcherActions(
                        onTabSelect = viewModel::selectTab,
                        onTabClose = viewModel::closeTab,
                        onNewTab = viewModel::openNewTab,
                        onCloseAll = viewModel::closeAllTabs,
                        onDismiss = { viewModel.setTabsSheetVisible(false) },
                    ),
            )
        }
    }
}

@Composable
private fun BrowserTopBar(
    tab: BrowserTab?,
    tabCount: Int,
    onNavigate: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onOpenTabs: () -> Unit,
    onNewTab: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onCloseTab: () -> Unit,
) {
    var urlText by rememberSaveable(tab?.id) { mutableStateOf(tab?.url.orEmpty()) }
    var isFocused by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(tab?.url) {
        if (!isFocused && tab != null) {
            urlText = tab.url
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_search_placeholder)) },
                leadingIcon = {
                    val isHttps = tab?.url?.startsWith("https://", ignoreCase = true) == true
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.browser_secure_connection),
                        tint = if (isHttps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (isFocused && urlText.isNotEmpty()) {
                        IconButton(onClick = { urlText = "" }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.action_clear),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else if (tab?.isLoading == true) {
                        IconButton(onClick = onStop) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_stop),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        IconButton(onClick = onReload) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.action_reload),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { onNavigate(urlText) }),
                shape = RoundedCornerShape(24.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenTabs),
            ) {
                Text(
                    text = tabCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.action_menu),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_new_tab)) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onNewTab()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_share_page)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_open_external)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenExternal()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_close_tab)) },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCloseTab()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RedirectBanner(
    notice: RedirectNotice,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column {
                    Text(
                        text = "${notice.serviceName} ➔ ${notice.frontendName}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.browser_redirect_banner_desc),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint =
                        if (canGoBack) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                )
            }

            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.action_forward),
                    tint =
                        if (canGoForward) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                )
            }

            IconButton(onClick = onHome) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = stringResource(R.string.action_home),
                )
            }

            IconButton(onClick = onTabs) {
                Icon(
                    Icons.Outlined.Tab,
                    contentDescription = stringResource(R.string.browser_tabs_fmt, tabCount),
                )
            }

            IconButton(onClick = onNewTab) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.tab_switcher_new_tab),
                )
            }
        }
    }
}

private fun Context.shareUrl(
    url: String,
    chooserTitle: String,
) {
    val sendIntent =
        Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, url)
            type = "text/plain"
        }
    val shareIntent = Intent.createChooser(sendIntent, chooserTitle)
    startActivity(shareIntent)
}

private fun Context.openExternalUrl(url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
    } catch (_: Exception) {
    }
}

@Composable
private fun key(
    key: Any?,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.key(key) {
        content()
    }
}
