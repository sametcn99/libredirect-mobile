@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    onImportBookmarks: (String, (Int) -> Unit) -> Unit = { _, _ -> },
    onExportBookmarksHtml: () -> String = { "" },
    onExportBookmarksJson: () -> String = { "" },
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
                    onImport = onImportBookmarks,
                    onExportHtml = onExportBookmarksHtml,
                    onExportJson = onExportBookmarksJson,
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
    onImport: (String, (Int) -> Unit) -> Unit,
    onExportHtml: () -> String,
    onExportJson: () -> String,
) {
    val context = LocalContext.current

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    val content =
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader(Charsets.UTF_8).readText()
                        }
                    if (!content.isNullOrBlank()) {
                        onImport(content) { count ->
                            val msg = context.getString(R.string.browser_bookmarks_imported_count, count)
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.browser_bookmarks_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    val exportHtmlLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/html"),
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    val html = onExportHtml()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(html.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, R.string.browser_bookmarks_export_success, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.browser_bookmarks_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    val exportJsonLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    val json = onExportJson()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, R.string.browser_bookmarks_export_success, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.browser_bookmarks_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    Column(modifier = Modifier.fillMaxWidth().height(380.dp)) {
        BookmarksHeaderActions(
            count = bookmarks.size,
            canExport = bookmarks.isNotEmpty(),
            onImport = { importLauncher.launch("*/*") },
            onExportHtml = { exportHtmlLauncher.launch("libredirect_bookmarks.html") },
            onExportJson = { exportJsonLauncher.launch("libredirect_bookmarks.json") },
        )

        if (bookmarks.isEmpty()) {
            BookmarksEmptyView(onImport = { importLauncher.launch("*/*") })
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(bookmarks, key = BrowserBookmark::id) { bookmark ->
                    BookmarkItemRow(
                        bookmark = bookmark,
                        onOpenUrl = onOpenUrl,
                        onDelete = onDelete,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun BookmarksHeaderActions(
    count: Int,
    canExport: Boolean,
    onImport: () -> Unit,
    onExportHtml: () -> Unit,
    onExportJson: () -> Unit,
) {
    var exportMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.browser_bookmarks_count_fmt, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onImport) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.browser_bookmarks_import))
            }

            Box {
                TextButton(
                    onClick = { exportMenuExpanded = true },
                    enabled = canExport,
                ) {
                    Icon(
                        Icons.Default.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.browser_bookmarks_export))
                }

                DropdownMenu(
                    expanded = exportMenuExpanded,
                    onDismissRequest = { exportMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_bookmarks_export_html)) },
                        onClick = {
                            exportMenuExpanded = false
                            onExportHtml()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_bookmarks_export_json)) },
                        onClick = {
                            exportMenuExpanded = false
                            onExportJson()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkItemRow(
    bookmark: BrowserBookmark,
    onOpenUrl: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
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
}

@Composable
private fun BookmarksEmptyView(onImport: () -> Unit) {
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
                Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.browser_bookmarks_empty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.browser_bookmarks_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onImport) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.browser_bookmarks_import))
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
    var isClearHistoryConfirmVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredHistory =
        if (searchQuery.isBlank()) {
            history
        } else {
            history.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.url.contains(searchQuery, ignoreCase = true)
            }
        }

    if (isClearHistoryConfirmVisible) {
        AlertDialog(
            onDismissRequest = { isClearHistoryConfirmVisible = false },
            title = {
                Text(
                    text = stringResource(R.string.browser_clear_history),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(text = stringResource(R.string.browser_clear_history_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isClearHistoryConfirmVisible = false
                        onClearAll()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { isClearHistoryConfirmVisible = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            shape = RoundedCornerShape(20.dp),
        )
    }

    if (history.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.browser_history_empty),
            description = stringResource(R.string.browser_history_empty_desc),
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth().height(380.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_history_search_hint)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_clear),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.browser_history_count_fmt, filteredHistory.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(onClick = { isClearHistoryConfirmVisible = true }) {
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
                items(filteredHistory, key = BrowserHistoryItem::id) { item ->
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

@Composable
private fun formatTimestamp(epochMillis: Long): String {
    val diff = System.currentTimeMillis() - epochMillis
    return when {
        diff < MINUTE_MILLIS -> stringResource(R.string.browser_time_just_now)
        diff < HOUR_MILLIS -> stringResource(R.string.browser_time_minutes_fmt, diff / MINUTE_MILLIS)
        diff < DAY_MILLIS -> stringResource(R.string.browser_time_hours_fmt, diff / HOUR_MILLIS)
        else -> stringResource(R.string.browser_time_days_fmt, diff / DAY_MILLIS)
    }
}
