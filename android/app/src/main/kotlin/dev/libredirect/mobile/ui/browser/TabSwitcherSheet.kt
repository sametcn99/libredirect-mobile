@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R

@Composable
fun TabSwitcherSheet(
    tabs: List<BrowserTab>,
    activeTabId: String,
    actions: TabSwitcherActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isCloseAllConfirmVisible by remember { mutableStateOf(false) }
    // 4.5: tab sort + lightweight grouping are sheet-local choices.
    var sortRecent by remember { mutableStateOf(true) }
    var groupTargetTabId by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf("") }

    val sortedTabs =
        if (sortRecent) {
            tabs.sortedByDescending { it.lastActiveAt }
        } else {
            tabs.sortedWith(compareBy<BrowserTab> { it.group ?: "" }.thenBy { tabs.indexOf(it) })
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
                    .padding(bottom = 16.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = stringResource(R.string.tab_switcher_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.tab_switcher_count_fmt, tabs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val incognitoDescription = stringResource(R.string.tab_switcher_new_incognito_tab)
                    IconButton(onClick = actions.onNewIncognitoTab) {
                        Icon(
                            Icons.Default.PrivacyTip,
                            contentDescription = incognitoDescription,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    FilledTonalButton(
                        onClick = actions.onNewTab,
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tab_switcher_new_tab))
                    }
                }
            }

            if (tabs.size > 1) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { sortRecent = !sortRecent },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (sortRecent) R.string.tab_switcher_sort_recent else R.string.tab_switcher_sort_order,
                            ),
                        )
                    }
                    TextButton(
                        onClick = { isCloseAllConfirmVisible = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tab_switcher_close_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
            ) {
                items(sortedTabs, key = BrowserTab::id) { tab ->
                    SwipeableTabCard(
                        tab = tab,
                        isActive = tab.id == activeTabId,
                        onSelect = { actions.onTabSelect(tab.id) },
                        onClose = { actions.onTabClose(tab.id) },
                        onGroup = { groupTargetTabId = tab.id },
                    )
                }
            }
        }
    }

    // Lightweight tab groups (4.5): type a name on the group sheet; grouped
    // tabs sort together and show a chip on the card.
    if (groupTargetTabId != null) {
        AlertDialog(
            onDismissRequest = { groupTargetTabId = null },
            title = { Text(stringResource(R.string.tab_switcher_group_add)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.tab_switcher_group_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        groupTargetTabId?.let { actions.onAssignGroup(it, groupName.ifBlank { null }) }
                        groupTargetTabId = null
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        groupTargetTabId?.let { actions.onAssignGroup(it, null) }
                        groupTargetTabId = null
                    },
                ) {
                    Text(stringResource(R.string.tab_switcher_group_remove))
                }
            },
        )
    }

    if (isCloseAllConfirmVisible) {
        AlertDialog(
            onDismissRequest = { isCloseAllConfirmVisible = false },
            title = { Text(stringResource(R.string.tab_switcher_close_all)) },
            text = { Text(stringResource(R.string.tab_switcher_close_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isCloseAllConfirmVisible = false
                        actions.onCloseAll()
                    },
                ) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    // 4.5: keep the current tab, drop the rest.
                    TextButton(
                        onClick = {
                            isCloseAllConfirmVisible = false
                            actions.onCloseOthers()
                        },
                    ) {
                        Text(stringResource(R.string.tab_switcher_close_others))
                    }
                    TextButton(onClick = { isCloseAllConfirmVisible = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

data class TabSwitcherActions(
    val onTabSelect: (String) -> Unit,
    val onTabClose: (String) -> Unit,
    val onNewTab: () -> Unit,
    val onNewIncognitoTab: () -> Unit,
    val onCloseAll: () -> Unit,
    /** Close every tab except the current one (4.5). */
    val onCloseOthers: () -> Unit = {},
    /** Assign the tab to a named group (or null to ungroup) — 4.5. */
    val onAssignGroup: (String, String?) -> Unit = { _, _ -> },
    val onDismiss: () -> Unit,
)

/**
 * Tab card wrapped in a swipe-to-dismiss container: swiping a card left or
 * right closes that tab (the X button still works too).
 */
@Composable
private fun SwipeableTabCard(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onGroup: () -> Unit = {},
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.Settled) {
                    false
                } else {
                    onClose()
                    true
                }
            },
        )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        TabCard(
            tab = tab,
            isActive = isActive,
            onSelect = onSelect,
            onClose = onClose,
            onGroup = onGroup,
        )
    }
}

@Composable
private fun TabCard(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onGroup: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (isActive) colors.primaryContainer else colors.surfaceContainerHigh,
                contentColor = if (isActive) colors.onPrimaryContainer else colors.onSurface,
            ),
        border = if (isActive) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 2.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.isIncognito) {
                Icon(
                    Icons.Default.PrivacyTip,
                    contentDescription = stringResource(R.string.tab_switcher_incognito_badge),
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            } else {
                SiteIcon(tab = tab, size = 18.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = tab.title.ifBlank { stringResource(R.string.tab_switcher_new_tab) },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.tab_switcher_close_tab),
                    modifier = Modifier.size(16.dp),
                )
            }
            if (tab.group != null) {
                Text(
                    text = tab.group.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        // Preview area: a live screenshot of the page as it last looked, or the
        // site's icon over its host for tabs that have never been active.
        // Long-press offers the tab-group dialog (4.5); Card handles taps.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    .combinedClickable(onClick = {}, onLongClick = onGroup)
                    .background(
                        color = if (isActive) colors.surfaceContainerLowest else colors.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            val thumbnail = tab.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!tab.isIncognito) SiteIcon(tab = tab, size = 40.dp)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        Icon(
                            imageVector = if (tab.isSecure) Icons.Default.Lock else Icons.Outlined.LockOpen,
                            contentDescription = null,
                            tint = if (tab.isSecure) colors.onSurfaceVariant else colors.error,
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = tab.displayHost,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
