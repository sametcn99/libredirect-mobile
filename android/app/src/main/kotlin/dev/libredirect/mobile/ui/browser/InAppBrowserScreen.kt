@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.libredirect.mobile.R
import kotlinx.coroutines.delay

private const val REDIRECT_NOTICE_DURATION_MS = 5_000L
private const val PROGRESS_MAX = 100f
private const val DISABLED_ALPHA = 0.38f
private const val PULL_REFRESH_THRESHOLD_DP = 96f
private const val PULL_INDICATOR_VISIBILITY = 0.02f

@Suppress("CyclomaticComplexMethod")
@Composable
fun InAppBrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val activeTab = state.activeTab
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val darkPages = state.pageTheme.isDark(isSystemInDarkTheme())
    var isAddressFocused by remember { mutableStateOf(false) }

    // File upload fields (onShowFileChooser): the WebView client hands us a
    // prepared intent; the system picker's result goes back to the callback.
    val fileChooserLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onFileChooserResult(result.resultCode, result.data)
        }
    LaunchedEffect(state.fileChooserRequest) {
        state.fileChooserRequest?.let { request ->
            fileChooserLauncher.launch(request.intent)
        }
    }

    val canHandleBack =
        state.isFullscreenVideo ||
            isAddressFocused ||
            state.isMenuSheetVisible ||
            state.isTabsSheetVisible ||
            state.isBookmarksHistorySheetVisible ||
            state.isSearchEngineDialogVisible ||
            state.isFindInPageVisible ||
            (activeTab?.canGoBack == true)

    BackHandler(enabled = canHandleBack) {
        when {
            state.isFullscreenVideo -> viewModel.exitFullscreenVideo()
            isAddressFocused -> focusManager.clearFocus()
            state.isMenuSheetVisible -> viewModel.setMenuSheetVisible(false)
            state.isTabsSheetVisible -> viewModel.setTabsSheetVisible(false)
            state.isBookmarksHistorySheetVisible -> viewModel.setBookmarksHistorySheetVisible(false)
            state.isSearchEngineDialogVisible -> viewModel.setSearchEngineDialogVisible(false)
            state.isFindInPageVisible -> viewModel.setFindInPageVisible(false)
            activeTab?.canGoBack == true -> viewModel.goBackActiveTab()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserTopBar(
                tab = activeTab,
                onNavigate = { query ->
                    viewModel.navigateActiveTab(query)
                    focusManager.clearFocus()
                },
                onReload = viewModel::reloadActiveTab,
                onStop = viewModel::stopActiveTab,
                onFocusChange = { isAddressFocused = it },
            )

            AnimatedVisibility(
                visible = state.isFindInPageVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                FindBar(
                    query = state.findQuery,
                    matchCount = state.findMatchCount,
                    activeMatchIndex = state.findActiveMatchIndex,
                    onQueryChange = viewModel::findInPage,
                    onPrevious = { viewModel.findNextInPage(false) },
                    onNext = { viewModel.findNextInPage(true) },
                    onClose = { viewModel.setFindInPageVisible(false) },
                )
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                var isWebViewAtTop by remember { mutableStateOf(true) }
                BrowserWebView(
                    activeTab = activeTab,
                    darkPages = darkPages,
                    viewModel = viewModel,
                    onAtTopChange = { isWebViewAtTop = it },
                    modifier = Modifier.fillMaxSize(),
                )

                if (activeTab?.pageError == true) {
                    PageErrorOverlay(
                        url = activeTab.url,
                        canTryAnotherInstance = activeTab.originalUrl != null,
                        onRetry = viewModel::reloadActiveTab,
                        onTryAnotherInstance = viewModel::openInstancePickerIfAvailable,
                        onOpenExternal = { context.openExternalUrl(activeTab.url) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                PullToRefreshHandle(
                    viewModel = viewModel,
                    isPageLoading = activeTab?.isLoading == true,
                    isWebViewAtTop = isWebViewAtTop,
                    modifier = Modifier.fillMaxSize(),
                )

                RedirectNoticeOverlay(
                    notice = state.activeRedirectNotice,
                    onDismiss = viewModel::dismissRedirectNotice,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            BrowserBottomBar(
                canGoBack = activeTab?.canGoBack == true,
                canGoForward = activeTab?.canGoForward == true,
                tabCount = state.tabs.size,
                onBack = { viewModel.goBackActiveTab() },
                onForward = { viewModel.goForwardActiveTab() },
                onHome = { viewModel.navigateActiveTab(state.searchEngine.homeUrl) },
                onTabs = { viewModel.setTabsSheetVisible(true) },
                onMenu = {
                    focusManager.clearFocus()
                    viewModel.setMenuSheetVisible(true)
                },
            )
        }

        FullscreenVideoOverlay(state = state)

        BrowserSheets(
            state = state,
            viewModel = viewModel,
            onShare = {
                activeTab?.url?.let {
                    context.shareUrl(it, context.getString(R.string.browser_share_link_title))
                }
            },
            onOpenExternal = { activeTab?.url?.let { context.openExternalUrl(it) } },
        )
    }
}

/**
 * Pull-to-refresh for the page area: observes drags in the final pass (the
 * WebView consumes them for its own scrolling), accumulating only downward
 * movement while the page sits at its top. Past the threshold the tab reloads
 * — the normal loading bar becomes the visual confirmation.
 */
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
@Composable
private fun PullToRefreshHandle(
    viewModel: BrowserViewModel,
    isPageLoading: Boolean,
    isWebViewAtTop: Boolean,
    modifier: Modifier = Modifier,
) {
    var pullProgress by remember { mutableFloatStateOf(0f) }
    var isArmed by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { PULL_REFRESH_THRESHOLD_DP.dp.toPx() }
    val isLoading by rememberUpdatedState(isPageLoading)
    val atTop by rememberUpdatedState(isWebViewAtTop)

    Box(
        modifier =
            modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        var distance = 0f
                        var triggered = false
                        isArmed = true
                        up@ while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull() ?: break
                            if (event.changes.all { !it.pressed }) break@up
                            if (isLoading) {
                                // A load started mid-gesture (the trigger or a
                                // link tap): ignore the rest of this drag.
                                distance = 0f
                                pullProgress = 0f
                                triggered = true
                                continue
                            }
                            val dy = change.positionChange().y
                            distance =
                                when {
                                    dy > 0f -> if (isArmed && atTop) distance + dy else distance
                                    else -> (distance + dy).coerceAtLeast(0f)
                                }
                            if (distance > 0f) isArmed = true
                            pullProgress = (distance / thresholdPx).coerceIn(0f, 1f)
                            if (!triggered && distance >= thresholdPx) {
                                triggered = true
                                viewModel.reloadActiveTab()
                            }
                        }
                        pullProgress = 0f
                    }
                }
            },
    ) {
        if (pullProgress > PULL_INDICATOR_VISIBILITY) {
            CircularProgressIndicator(
                progress = { pullProgress },
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .size(30.dp),
            )
        }
    }
}

/** In-app error page shown over a failed main-frame load (A2). */
@Composable
private fun PageErrorOverlay(
    url: String,
    canTryAnotherInstance: Boolean,
    onRetry: () -> Unit,
    onTryAnotherInstance: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.browser_page_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = BrowserTab.hostOf(url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.browser_page_error_retry))
            }
            if (canTryAnotherInstance) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onTryAnotherInstance) {
                    Text(stringResource(R.string.browser_menu_try_another_instance))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenExternal) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.browser_menu_open_external))
            }
        }
    }
}

/** Covers the whole screen while a page renders its own fullscreen `<video>` view. */
@Composable
private fun FullscreenVideoOverlay(state: BrowserUiState) {
    if (!state.isFullscreenVideo) return
    val customView = state.fullscreenView ?: return
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        key(customView) {
            AndroidView(
                factory = { customView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Keyed on the effective page theme as well as the tab: flipping between light
 * and dark recreates the AndroidView, and [BrowserViewModel.getOrCreateWebView]
 * swaps in a WebView themed to match.
 */
@Composable
private fun BrowserWebView(
    activeTab: BrowserTab?,
    darkPages: Boolean,
    viewModel: BrowserViewModel,
    onAtTopChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (activeTab != null) {
            key(activeTab.id, darkPages) {
                val atTopCallback by rememberUpdatedState(onAtTopChange)
                AndroidView(
                    factory = { ctx ->
                        viewModel
                            .getOrCreateWebView(activeTab.id, ctx, darkPages)
                            .apply {
                                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                                    atTopCallback(scrollY <= 0)
                                }
                            }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun BrowserSheets(
    state: BrowserUiState,
    viewModel: BrowserViewModel,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    state.linkContextUrl?.let { url ->
        LinkContextMenuDialog(
            url = url,
            onOpenInNewTab = { viewModel.openLinkInNewTab(url) },
            onCopy = { viewModel.copyLinkToClipboard(url) },
            onShare = {
                viewModel.dismissLinkContextMenu()
                context.shareUrl(url, context.getString(R.string.browser_share_link_title))
            },
            onOpenExternal = {
                viewModel.dismissLinkContextMenu()
                context.openExternalUrl(url)
            },
            onDismiss = viewModel::dismissLinkContextMenu,
        )
    }

    if (state.isMenuSheetVisible) {
        BrowserMenuSheet(
            tab = state.activeTab,
            isBookmarked = state.isCurrentPageBookmarked,
            searchEngine = state.searchEngine,
            pageTheme = state.pageTheme,
            autoDarken = state.autoDarken,
            instanceAlternatives = state.instanceAlternatives,
            actions =
                BrowserMenuActions(
                    onNewTab = { viewModel.openNewTab() },
                    onNewIncognitoTab = { viewModel.openIncognitoTab() },
                    onToggleBookmark = viewModel::toggleBookmarkActivePage,
                    onShare = onShare,
                    onFindInPage = { viewModel.setFindInPageVisible(true) },
                    onToggleDesktopMode = viewModel::toggleDesktopModeActiveTab,
                    onPageThemeChange = viewModel::setPageTheme,
                    onAutoDarkenChange = viewModel::setAutoDarken,
                    onOpenBookmarks = { viewModel.setBookmarksHistorySheetVisible(true, initialTab = 0) },
                    onOpenHistory = { viewModel.setBookmarksHistorySheetVisible(true, initialTab = 1) },
                    onOpenSearchEngine = { viewModel.setSearchEngineDialogVisible(true) },
                    onTryAnotherInstance = { viewModel.setInstancePickerVisible(true) },
                    onOpenExternal = onOpenExternal,
                    onClearBrowsingData = { viewModel.setClearDataConfirmVisible(true) },
                    onCloseTab = { state.activeTab?.let { viewModel.closeTab(it.id) } },
                    onDismiss = { viewModel.setMenuSheetVisible(false) },
                ),
        )
    }

    if (state.isClearDataConfirmVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.setClearDataConfirmVisible(false) },
            title = { Text(stringResource(R.string.browser_menu_clear_cookies_cache)) },
            text = { Text(stringResource(R.string.browser_clear_data_confirm)) },
            confirmButton = {
                TextButton(onClick = viewModel::clearBrowsingData) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setClearDataConfirmVisible(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.isInstancePickerVisible) {
        state.instanceAlternatives?.let { alternatives ->
            InstancePickerDialog(
                alternatives = alternatives,
                latencies = state.instanceLatencies,
                isMeasuring = state.isMeasuringInstances,
                onMeasure = viewModel::measureInstanceLatency,
                onPickRandom = { viewModel.tryAnotherInstance(null) },
                onPickInstance = viewModel::tryAnotherInstance,
                onDismiss = { viewModel.setInstancePickerVisible(false) },
            )
        }
    }

    if (state.isTabsSheetVisible) {
        TabSwitcherSheet(
            tabs = state.tabs,
            activeTabId = state.activeTabId,
            actions =
                TabSwitcherActions(
                    onTabSelect = viewModel::selectTab,
                    onTabClose = viewModel::closeTab,
                    onNewTab = { viewModel.openNewTab() },
                    onNewIncognitoTab = { viewModel.openIncognitoTab() },
                    onCloseAll = viewModel::closeAllTabs,
                    onDismiss = { viewModel.setTabsSheetVisible(false) },
                ),
        )
    }

    if (state.isBookmarksHistorySheetVisible) {
        BookmarksHistorySheet(
            bookmarks = state.bookmarks,
            history = state.history,
            initialTab = state.bookmarksHistoryInitialTab,
            onOpenUrl = { url ->
                viewModel.openUrlInNewOrActiveTab(url)
            },
            onDeleteBookmark = viewModel::deleteBookmark,
            onDeleteHistoryItem = viewModel::deleteHistoryItem,
            onClearHistory = viewModel::clearHistory,
            onImportBookmarks = { content, onDone ->
                viewModel.importBookmarks(content, onDone)
            },
            onExportBookmarksHtml = viewModel::exportBookmarksHtml,
            onExportBookmarksJson = viewModel::exportBookmarksJson,
            onDismiss = { viewModel.setBookmarksHistorySheetVisible(false) },
        )
    }

    if (state.isSearchEngineDialogVisible) {
        SearchEngineDialog(
            currentEngine = state.searchEngine,
            customEngine = SearchEngine.customOrNull(state.customSearchEngineName, state.customSearchEngineUrl),
            onSelectEngine = viewModel::setSearchEngine,
            onSaveCustom = viewModel::saveCustomSearchEngine,
            onDismiss = { viewModel.setSearchEngineDialogVisible(false) },
        )
    }
}

@Composable
private fun LinkContextMenuDialog(
    url: String,
    onOpenInNewTab: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.browser_link_context_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                LinkActionRow(
                    icon = Icons.Default.Add,
                    label = stringResource(R.string.browser_link_context_open_new_tab),
                    onClick = onOpenInNewTab,
                )
                LinkActionRow(
                    icon = Icons.Default.ContentCopy,
                    label = stringResource(R.string.browser_link_context_copy),
                    onClick = onCopy,
                )
                LinkActionRow(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.browser_link_context_share),
                    onClick = onShare,
                )
                LinkActionRow(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    label = stringResource(R.string.browser_menu_open_external),
                    onClick = onOpenExternal,
                )
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
private fun LinkActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BrowserTopBar(
    tab: BrowserTab?,
    onNavigate: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val isLoading = tab?.isLoading == true
    val progress by animateFloatAsState(
        targetValue = (tab?.progress ?: 0) / PROGRESS_MAX,
        label = "pageLoadProgress",
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                AddressBar(
                    tab = tab,
                    onNavigate = onNavigate,
                    onReload = onReload,
                    onStop = onStop,
                    onFocusChange = onFocusChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Fixed-height slot so the page doesn't jump when loading starts or ends.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressBar(
    tab: BrowserTab?,
    onNavigate: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = tab?.url.orEmpty()
    var isFocused by remember { mutableStateOf(false) }
    var fieldValue by remember(tab?.id) { mutableStateOf(TextFieldValue(url)) }

    LaunchedEffect(url, isFocused) {
        if (!isFocused) fieldValue = TextFieldValue(url)
    }
    // Select the whole URL once focused so typing replaces it; done after the
    // focus frame so the tap that focused the field doesn't reset the selection.
    LaunchedEffect(isFocused) {
        if (isFocused) fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
    }

    val shape = CircleShape
    val shownValue = if (isFocused) fieldValue else TextFieldValue(tab?.displayHost.orEmpty())
    val textStyle =
        MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isFocused) FontWeight.Normal else FontWeight.Medium,
        )

    BasicTextField(
        value = shownValue,
        onValueChange = { fieldValue = it },
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
        keyboardActions = KeyboardActions(onGo = { onNavigate(fieldValue.text) }),
        modifier =
            modifier.onFocusChanged {
                if (it.isFocused != isFocused) {
                    isFocused = it.isFocused
                    onFocusChange(it.isFocused)
                }
            },
        decorationBox = { innerTextField ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(shape)
                        .background(
                            if (isFocused) {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ).then(
                            if (isFocused) {
                                Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                            } else {
                                Modifier
                            },
                        ).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteSecurityIcon(tab = tab, isEditing = isFocused)
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (shownValue.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.browser_search_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                AddressBarTrailingAction(
                    isEditing = isFocused,
                    hasText = fieldValue.text.isNotEmpty(),
                    isLoading = tab?.isLoading == true,
                    onClear = { fieldValue = TextFieldValue("") },
                    onReload = onReload,
                    onStop = onStop,
                )
            }
        },
    )
}

@Composable
private fun SiteSecurityIcon(
    tab: BrowserTab?,
    isEditing: Boolean,
) {
    val (icon, tint, description) =
        when {
            isEditing || tab == null ->
                Triple(Icons.Default.Search, MaterialTheme.colorScheme.onSurfaceVariant, null)

            tab.isSecure ->
                Triple(
                    Icons.Default.Lock,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.browser_secure_connection),
                )

            else ->
                Triple(
                    Icons.Outlined.LockOpen,
                    MaterialTheme.colorScheme.error,
                    stringResource(R.string.browser_not_secure),
                )
        }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun AddressBarTrailingAction(
    isEditing: Boolean,
    hasText: Boolean,
    isLoading: Boolean,
    onClear: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
) {
    when {
        isEditing && hasText ->
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Outlined.Cancel,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

        isEditing -> Spacer(Modifier.width(12.dp))

        isLoading ->
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_stop),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

        else ->
            IconButton(onClick = onReload) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.action_reload),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
    }
}

@Composable
private fun FindBar(
    query: String,
    matchCount: Int,
    activeMatchIndex: Int,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.browser_find_hint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                            if (query.isNotEmpty()) {
                                Text(
                                    text =
                                        stringResource(
                                            R.string.browser_find_matches_fmt,
                                            activeMatchIndex,
                                            matchCount,
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color =
                                        if (matchCount > 0) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                )
                            }
                        }
                    },
                )

                IconButton(onClick = onPrevious, enabled = matchCount > 0) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.browser_find_previous),
                    )
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.browser_find_next),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * Floats over the top of the page instead of pushing it down, and hides
 * itself after a few seconds.
 */
@Composable
private fun RedirectNoticeOverlay(
    notice: RedirectNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep showing the last notice while the exit animation runs.
    var lastNotice by remember { mutableStateOf(notice) }
    if (notice != null) lastNotice = notice

    LaunchedEffect(notice?.timestamp) {
        if (notice != null) {
            delay(REDIRECT_NOTICE_DURATION_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier,
    ) {
        lastNotice?.let { shown ->
            RedirectNoticeCard(notice = shown, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun RedirectNoticeCard(
    notice: RedirectNotice,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inversePrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            R.string.browser_redirect_from_to_fmt,
                            notice.serviceName,
                            notice.frontendName,
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.browser_redirect_banner_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    modifier = Modifier.size(18.dp),
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
    onMenu: () -> Unit,
) {
    // Same container as the app's NavigationBar below it, so the two read as one surface.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    enabled = canGoBack,
                    onClick = onBack,
                )
                ToolbarButton(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.action_forward),
                    enabled = canGoForward,
                    onClick = onForward,
                )
                ToolbarButton(
                    icon = Icons.Outlined.Home,
                    contentDescription = stringResource(R.string.action_home),
                    onClick = onHome,
                )
                val tabsDescription = stringResource(R.string.browser_tabs_fmt, tabCount)
                IconButton(
                    onClick = onTabs,
                    modifier = Modifier.semantics { contentDescription = tabsDescription },
                ) {
                    TabCountBadge(count = tabCount)
                }
                ToolbarButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_menu),
                    onClick = onMenu,
                )
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                },
        )
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
