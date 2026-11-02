@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R

private const val TAB_BOOKMARKS = 0
private const val TAB_HISTORY = 1
private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 86_400_000L

@Composable
fun BookmarksHistorySheet(
    bookmarks: List<BrowserBookmark>,
    history: List<BrowserHistoryItem>,
    initialTab: Int = TAB_BOOKMARKS,
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHistoryItem: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.weight(1f),
                ) {
                    Tab(
                        selected = selectedTab == TAB_BOOKMARKS,
                        onClick = { selectedTab = TAB_BOOKMARKS },
                        text = { Text(stringResource(R.string.browser_bookmarks_title)) },
                        icon = {
                            val icon =
                                if (selectedTab == TAB_BOOKMARKS) {
                                    Icons.Filled.Bookmark
                                } else {
                                    Icons.Outlined.BookmarkBorder
                                }
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == TAB_HISTORY,
                        onClick = { selectedTab = TAB_HISTORY },
                        text = { Text(stringResource(R.string.browser_history_title)) },
                        icon = {
                            val icon =
                                if (selectedTab == TAB_HISTORY) {
                                    Icons.Filled.History
                                } else {
                                    Icons.Outlined.History
                                }
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (selectedTab == TAB_BOOKMARKS) {
                BookmarksTabContent(
                    bookmarks = bookmarks,
                    onOpenUrl = { url ->
                        onOpenUrl(url)
                        onDismiss()
                    },
                    onDelete = onDeleteBookmark,
                )
            } else {
                HistoryTabContent(
                    history = history,
                    onOpenUrl = { url ->
                        onOpenUrl(url)
                        onDismiss()
                    },
                    onDelete = onDeleteHistoryItem,
                    onClearAll = onClearHistory,
                )
            }
        }
    }
}

@Composable
private fun BookmarksTabContent(
    bookmarks: List<BrowserBookmark>,
    onOpenUrl: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.BookmarkBorder,
            title = stringResource(R.string.browser_bookmarks_empty),
            description = stringResource(R.string.browser_bookmarks_empty_desc),
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().height(380.dp),
        ) {
            items(bookmarks, key = BrowserBookmark::id) { bookmark ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrl(bookmark.url) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookmark.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = bookmark.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    IconButton(
                        onClick = { onDelete(bookmark.id) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.browser_delete_item),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun HistoryTabContent(
    history: List<BrowserHistoryItem>,
    onOpenUrl: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    if (history.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.browser_history_empty),
            description = stringResource(R.string.browser_history_empty_desc),
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth().height(380.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${history.size} items",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(onClick = onClearAll) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.browser_clear_history))
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(history, key = BrowserHistoryItem::id) { item ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUrl(item.url) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Text(
                                    text = formatTimestamp(item.visitedAtEpochMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }

                        IconButton(
                            onClick = { onDelete(item.id) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.browser_delete_item),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    val diff = System.currentTimeMillis() - epochMillis
    return when {
        diff < MINUTE_MILLIS -> "Just now"
        diff < HOUR_MILLIS -> "${diff / MINUTE_MILLIS}m"
        diff < DAY_MILLIS -> "${diff / HOUR_MILLIS}h"
        else -> "${diff / DAY_MILLIS}d"
    }
}
