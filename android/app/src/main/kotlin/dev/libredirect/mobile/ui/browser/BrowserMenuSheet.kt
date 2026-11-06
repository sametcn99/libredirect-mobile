@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("MatchingDeclarationName")

package dev.libredirect.mobile.ui.browser

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R

data class BrowserMenuActions(
    val onNewTab: () -> Unit,
    val onNewIncognitoTab: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onShare: () -> Unit,
    val onFindInPage: () -> Unit,
    val onToggleDesktopMode: () -> Unit,
    val onPageThemeChange: (PageTheme) -> Unit,
    val onAutoDarkenChange: (Boolean) -> Unit,
    val onOpenBookmarks: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onOpenSearchEngine: () -> Unit,
    val onTryAnotherInstance: () -> Unit,
    val onOpenExternal: () -> Unit,
    val onClearBrowsingData: () -> Unit,
    val onCloseTab: () -> Unit,
    val onToggleReaderMode: () -> Unit = {},
    val onPrint: () -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onShareOriginal: () -> Unit = {},
    val onCopyOriginal: () -> Unit = {},
    val onDismiss: () -> Unit,
)

@Composable
fun BrowserMenuSheet(
    tab: BrowserTab?,
    isBookmarked: Boolean,
    searchEngine: SearchEngine,
    pageTheme: PageTheme,
    autoDarken: Boolean,
    textZoom: Int,
    instanceAlternatives: InstanceAlternatives?,
    blockedCount: Int,
    onTextZoomChange: (Int) -> Unit,
    actions: BrowserMenuActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Every action except the in-place toggles closes the sheet first.
    fun dismissThen(action: () -> Unit): () -> Unit =
        {
            actions.onDismiss()
            action()
        }

    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            MenuHeader(
                tab = tab,
                isBookmarked = isBookmarked,
                blockedCount = blockedCount,
                onToggleBookmark = actions.onToggleBookmark,
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickAction(
                    icon = Icons.Default.Add,
                    labelRes = R.string.browser_menu_new_tab,
                    onClick = dismissThen(actions.onNewTab),
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    icon = Icons.Default.Share,
                    labelRes = R.string.browser_quick_share,
                    onClick = dismissThen(actions.onShare),
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    icon = Icons.Default.FindInPage,
                    labelRes = R.string.browser_quick_find,
                    onClick = dismissThen(actions.onFindInPage),
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    icon = Icons.Default.DesktopWindows,
                    labelRes = R.string.browser_quick_desktop,
                    selected = tab?.isDesktopMode == true,
                    onClick = actions.onToggleDesktopMode,
                    modifier = Modifier.weight(1f),
                )
            }

            PageAppearanceCard(
                pageTheme = pageTheme,
                autoDarken = autoDarken,
                textZoom = textZoom,
                onPageThemeChange = actions.onPageThemeChange,
                onAutoDarkenChange = actions.onAutoDarkenChange,
                onTextZoomChange = onTextZoomChange,
            )

            Spacer(Modifier.height(8.dp))

            MenuRow(
                icon = Icons.Default.PrivacyTip,
                label = stringResource(R.string.browser_menu_new_incognito_tab),
                onClick = dismissThen(actions.onNewIncognitoTab),
            )
            MenuRow(
                icon = Icons.Outlined.Bookmarks,
                label = stringResource(R.string.browser_bookmarks_title),
                onClick = dismissThen(actions.onOpenBookmarks),
            )
            MenuRow(
                icon = Icons.Outlined.History,
                label = stringResource(R.string.browser_history_title),
                onClick = dismissThen(actions.onOpenHistory),
            )
            MenuRow(
                icon = Icons.Default.Search,
                label = stringResource(R.string.browser_menu_search_engine),
                trailing = searchEngine.displayName,
                onClick = dismissThen(actions.onOpenSearchEngine),
            )
            if (instanceAlternatives != null) {
                MenuRow(
                    icon = Icons.Default.SwapHoriz,
                    label = stringResource(R.string.browser_menu_try_another_instance),
                    trailing = instanceAlternatives.frontendName,
                    onClick = dismissThen(actions.onTryAnotherInstance),
                )
            }
            // Original-link actions (2.7): share/copy the pre-redirect URL.
            if (tab?.originalUrl != null) {
                MenuRow(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.browser_menu_share_original),
                    onClick = dismissThen(actions.onShareOriginal),
                )
                MenuRow(
                    icon = Icons.Default.ContentCopy,
                    label = stringResource(R.string.browser_menu_copy_original),
                    onClick = dismissThen(actions.onCopyOriginal),
                )
            }
            MenuRow(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                label =
                    stringResource(
                        if (tab?.isReaderMode == true) {
                            R.string.browser_reader_exit
                        } else {
                            R.string.browser_menu_reader_mode
                        },
                    ),
                onClick = dismissThen(actions.onToggleReaderMode),
            )
            MenuRow(
                icon = Icons.Default.Print,
                label = stringResource(R.string.browser_menu_print),
                onClick = dismissThen(actions.onPrint),
            )
            MenuRow(
                icon = Icons.Default.Download,
                label = stringResource(R.string.browser_menu_downloads),
                onClick = dismissThen(actions.onOpenDownloads),
            )
            MenuRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = stringResource(R.string.browser_menu_open_external),
                onClick = dismissThen(actions.onOpenExternal),
            )
            MenuRow(
                icon = Icons.Default.DeleteSweep,
                label = stringResource(R.string.browser_menu_clear_cookies_cache),
                onClick = dismissThen(actions.onClearBrowsingData),
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )

            MenuRow(
                icon = Icons.Default.Close,
                label = stringResource(R.string.browser_menu_close_tab),
                tint = MaterialTheme.colorScheme.error,
                onClick = dismissThen(actions.onCloseTab),
            )
        }
    }
}

@Composable
private fun MenuHeader(
    tab: BrowserTab?,
    isBookmarked: Boolean,
    blockedCount: Int,
    onToggleBookmark: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(tab = tab, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab?.title?.ifBlank { null } ?: stringResource(R.string.tab_switcher_new_tab),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val secure = tab?.isSecure == true
                Icon(
                    imageVector = if (secure) Icons.Default.Lock else Icons.Outlined.LockOpen,
                    contentDescription =
                        stringResource(if (secure) R.string.browser_secure_connection else R.string.browser_not_secure),
                    tint = if (secure) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = tab?.displayHost.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The tab is serving a redirected frontend: surface the original link it is really about.
            val originalHost = tab?.originalUrl?.let { BrowserTab.hostOf(it) }
            if (originalHost != null && originalHost != tab?.displayHost) {
                Text(
                    text = stringResource(R.string.browser_original_host_fmt, originalHost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 4.2: per-page blocked-request counter.
            if (blockedCount > 0) {
                Text(
                    text = stringResource(R.string.browser_blocked_fmt, blockedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription =
                    stringResource(
                        if (isBookmarked) R.string.browser_action_remove_bookmark else R.string.browser_action_bookmark,
                    ),
                tint =
                    if (isBookmarked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PageAppearanceCard(
    pageTheme: PageTheme,
    autoDarken: Boolean,
    textZoom: Int,
    onPageThemeChange: (PageTheme) -> Unit,
    onAutoDarkenChange: (Boolean) -> Unit,
    onTextZoomChange: (Int) -> Unit,
) {
    var isTextZoomDialogVisible by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Contrast,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.browser_page_appearance),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(12.dp))

            val options = PageTheme.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == pageTheme,
                        onClick = { onPageThemeChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(stringResource(option.labelRes), maxLines = 1)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAutoDarkenChange(!autoDarken) }
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.browser_auto_darken),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.browser_auto_darken_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoDarken, onCheckedChange = onAutoDarkenChange)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { isTextZoomDialogVisible = true }
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.browser_menu_text_size),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.browser_text_zoom_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.browser_text_zoom_percent_fmt, textZoom),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (isTextZoomDialogVisible) {
        TextZoomDialog(
            currentZoom = textZoom,
            onSelect = {
                onTextZoomChange(it)
                isTextZoomDialogVisible = false
            },
            onDismiss = { isTextZoomDialogVisible = false },
        )
    }
}

@Composable
private fun TextZoomDialog(
    currentZoom: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = TEXT_ZOOM_OPTIONS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.browser_menu_text_size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { percent ->
                    val isSelected = percent == currentZoom
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(percent) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelect(percent) },
                        )
                        Text(
                            text = stringResource(R.string.browser_text_zoom_percent_fmt, percent),
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

@Suppress("MagicNumber")
private val TEXT_ZOOM_OPTIONS = listOf(100, 125, 150, 175, 200)

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                if (tint == MaterialTheme.colorScheme.onSurface) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    tint
                },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@get:StringRes
private val PageTheme.labelRes: Int
    get() =
        when (this) {
            PageTheme.SYSTEM -> R.string.browser_page_theme_system
            PageTheme.LIGHT -> R.string.browser_page_theme_light
            PageTheme.DARK -> R.string.browser_page_theme_dark
        }
