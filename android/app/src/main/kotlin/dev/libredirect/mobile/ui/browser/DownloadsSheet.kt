package dev.libredirect.mobile.ui.browser

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.downloads.DownloadCategory
import dev.libredirect.mobile.downloads.DownloadFailureReason
import dev.libredirect.mobile.downloads.DownloadItem
import dev.libredirect.mobile.downloads.DownloadSourceKind
import dev.libredirect.mobile.downloads.DownloadStatus
import dev.libredirect.mobile.downloads.EtaParts
import dev.libredirect.mobile.downloads.categorizeDownload
import dev.libredirect.mobile.downloads.etaParts
import dev.libredirect.mobile.downloads.fileExtensionOf
import dev.libredirect.mobile.downloads.filterDownloads
import dev.libredirect.mobile.downloads.formatByteSize

private val APK_EXTENSIONS = setOf("apk", "xapk", "apks", "aab")
private const val TRACK_ALPHA = 0.25f

data class DownloadsSheetActions(
    val onDismiss: () -> Unit,
    val onOpenFile: (DownloadItem) -> Unit,
    val onShareFile: (DownloadItem) -> Unit,
    val onCopyUrl: (DownloadItem) -> Unit,
    val onPause: (String) -> Unit,
    val onPauseAll: () -> Unit,
    val onResume: (id: String, allowMeteredOverride: Boolean) -> Unit,
    val onCancel: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onRemove: (id: String, deleteFile: Boolean) -> Unit,
    val onClearFinished: (deleteFiles: Boolean) -> Unit,
    val onOpenSystemDownloads: () -> Unit,
)

private data class PendingDeleteDialog(
    val item: DownloadItem,
    val defaultDeleteDisk: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(
    downloads: List<DownloadItem>,
    actions: DownloadsSheetActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(DownloadCategory.ALL) }
    var headerMenuExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDeleteDialog?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val activeCount = remember(downloads) { downloads.count { it.status.isActive } }
    val hasPausableHttp =
        remember(downloads) {
            downloads.any { it.sourceKind == DownloadSourceKind.HTTP && it.status.isActive }
        }
    val hasFinishedItems =
        remember(downloads) {
            downloads.any { !it.status.isInProgressOrPaused }
        }
    val filtered =
        remember(downloads, searchQuery, selectedCategory) {
            filterDownloads(downloads, searchQuery, selectedCategory)
        }

    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
        ) {
            DownloadsSheetHeader(
                activeCount = activeCount,
                hasPausableHttp = hasPausableHttp,
                hasFinishedItems = hasFinishedItems,
                menuExpanded = headerMenuExpanded,
                onMenuExpandedChange = { headerMenuExpanded = it },
                onPauseAll = actions.onPauseAll,
                onRequestClearFinished = { showClearConfirm = true },
                onOpenSystemDownloads = actions.onOpenSystemDownloads,
                onDismiss = actions.onDismiss,
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.downloads_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(DownloadCategory.entries) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(stringResource(categoryLabelRes(category))) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            when {
                downloads.isEmpty() -> EmptyDownloadsPlaceholder(isFiltered = false)
                filtered.isEmpty() -> EmptyDownloadsPlaceholder(isFiltered = true)
                else ->
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp),
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            DownloadItemCard(
                                item = item,
                                actions = actions,
                                onRequestDelete = { defaultDeleteDisk ->
                                    pendingDelete = PendingDeleteDialog(item, defaultDeleteDisk)
                                },
                            )
                        }
                    }
            }
        }
    }

    pendingDelete?.let { dialog ->
        DeleteDownloadConfirmDialog(
            item = dialog.item,
            defaultDeleteDisk = dialog.defaultDeleteDisk,
            onConfirm = { deleteFile ->
                actions.onRemove(dialog.item.id, deleteFile)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showClearConfirm) {
        val hasDeletableCompleted =
            downloads.any { it.status == DownloadStatus.COMPLETED && !it.fileMissing }
        ClearFinishedConfirmDialog(
            canDeleteFiles = hasDeletableCompleted,
            onConfirm = { deleteFiles ->
                actions.onClearFinished(deleteFiles)
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

@Composable
private fun DownloadsSheetHeader(
    activeCount: Int,
    hasPausableHttp: Boolean,
    hasFinishedItems: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onPauseAll: () -> Unit,
    onRequestClearFinished: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.downloads_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (activeCount > 0) {
                Text(
                    text = stringResource(R.string.downloads_active_summary_fmt, activeCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (hasPausableHttp) {
            IconButton(onClick = onPauseAll) {
                Icon(
                    imageVector = Icons.Default.PauseCircleOutline,
                    contentDescription = stringResource(R.string.downloads_pause_all),
                )
            }
        }
        Box {
            IconButton(onClick = { onMenuExpandedChange(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuExpandedChange(false) },
            ) {
                if (hasFinishedItems) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                        text = { Text(stringResource(R.string.downloads_clear_finished)) },
                        onClick = {
                            onMenuExpandedChange(false)
                            onRequestClearFinished()
                        },
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    text = { Text(stringResource(R.string.downloads_open_system)) },
                    onClick = {
                        onMenuExpandedChange(false)
                        onOpenSystemDownloads()
                    },
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun EmptyDownloadsPlaceholder(isFiltered: Boolean) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isFiltered) Icons.Default.Search else Icons.Default.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text =
                stringResource(
                    if (isFiltered) R.string.downloads_empty_filtered else R.string.downloads_empty,
                ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!isFiltered) {
            Text(
                text = stringResource(R.string.downloads_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    actions: DownloadsSheetActions,
    onRequestDelete: (defaultDeleteDisk: Boolean) -> Unit,
) {
    var rowMenuExpanded by remember { mutableStateOf(false) }
    val canOpen = item.status == DownloadStatus.COMPLETED && !item.fileMissing
    val containerColor =
        if (item.status == DownloadStatus.RUNNING) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = canOpen) { actions.onOpenFile(item) },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DownloadFileIconBox(item = item)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    DownloadBadgesRow(item = item)

                    DownloadStatusDetails(item = item)
                }

                DownloadRowTrailingControls(
                    item = item,
                    menuExpanded = rowMenuExpanded,
                    onMenuExpandedChange = { rowMenuExpanded = it },
                    actions = actions,
                    onRequestDelete = onRequestDelete,
                )
            }

            DownloadProgressSection(item = item, onResumeMetered = actions.onResume)
        }
    }
}

@Composable
private fun DownloadFileIconBox(item: DownloadItem) {
    val icon = fileTypeIcon(item)
    val (bgColor, tint) =
        when (item.status) {
            DownloadStatus.COMPLETED ->
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            DownloadStatus.FAILED ->
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            DownloadStatus.WAITING_FOR_WIFI ->
                MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            else ->
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
    Box(
        modifier =
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun DownloadBadgesRow(item: DownloadItem) {
    val showBadges = item.isIncognito || item.isPrivateStorage || item.strippedTrackingCount > 0
    if (!showBadges) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isIncognito) {
            SmallBadgePill(
                text = stringResource(R.string.downloads_incognito_badge),
                icon = Icons.Default.Shield,
            )
        }
        if (item.strippedTrackingCount > 0) {
            SmallBadgePill(
                text = stringResource(R.string.downloads_stripped_badge_fmt, item.strippedTrackingCount),
            )
        }
        if (item.isPrivateStorage) {
            SmallBadgePill(text = stringResource(R.string.downloads_private_badge))
        }
    }
}

@Composable
private fun SmallBadgePill(
    text: String,
    icon: ImageVector? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DownloadStatusDetails(item: DownloadItem) {
    val statusText = downloadSecondaryLine(item)
    val isError = item.status == DownloadStatus.FAILED || (item.status == DownloadStatus.COMPLETED && item.fileMissing)
    Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (item.sourceHost.isNotBlank()) {
        Text(
            text = item.sourceHost,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadRowTrailingControls(
    item: DownloadItem,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    actions: DownloadsSheetActions,
    onRequestDelete: (defaultDeleteDisk: Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DownloadPrimaryActionButtons(item = item, actions = actions)

        Box {
            IconButton(onClick = { onMenuExpandedChange(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            DownloadItemOverflowMenu(
                item = item,
                expanded = menuExpanded,
                onDismiss = { onMenuExpandedChange(false) },
                actions = actions,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

@Composable
private fun DownloadPrimaryActionButtons(
    item: DownloadItem,
    actions: DownloadsSheetActions,
) {
    when (item.status) {
        DownloadStatus.RUNNING,
        DownloadStatus.QUEUED,
        -> {
            if (item.sourceKind == DownloadSourceKind.HTTP) {
                IconButton(onClick = { actions.onPause(item.id) }) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = stringResource(R.string.downloads_action_pause),
                    )
                }
            }
            IconButton(onClick = { actions.onCancel(item.id) }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
        DownloadStatus.PAUSED -> {
            IconButton(onClick = { actions.onResume(item.id, false) }) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.downloads_action_resume),
                )
            }
            IconButton(onClick = { actions.onCancel(item.id) }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
        DownloadStatus.WAITING_FOR_WIFI -> {
            IconButton(onClick = { actions.onCancel(item.id) }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
        DownloadStatus.FAILED,
        DownloadStatus.CANCELED,
        -> {
            if (item.sourceKind == DownloadSourceKind.HTTP) {
                IconButton(onClick = { actions.onRetry(item.id) }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.downloads_action_retry),
                    )
                }
            }
        }
        DownloadStatus.COMPLETED -> {
            if (!item.fileMissing) {
                IconButton(onClick = { actions.onShareFile(item) }) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.downloads_action_share),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItemOverflowMenu(
    item: DownloadItem,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: DownloadsSheetActions,
    onRequestDelete: (defaultDeleteDisk: Boolean) -> Unit,
) {
    val hasLocalFile = item.status == DownloadStatus.COMPLETED && !item.fileMissing
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (hasLocalFile) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                text = { Text(stringResource(R.string.downloads_action_open)) },
                onClick = {
                    onDismiss()
                    actions.onOpenFile(item)
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                text = { Text(stringResource(R.string.downloads_action_share)) },
                onClick = {
                    onDismiss()
                    actions.onShareFile(item)
                },
            )
        }
        if (item.sourceKind == DownloadSourceKind.HTTP) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                text = { Text(stringResource(R.string.downloads_action_copy_url)) },
                onClick = {
                    onDismiss()
                    actions.onCopyUrl(item)
                },
            )
        }
        if (hasLocalFile) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                text = { Text(stringResource(R.string.downloads_action_delete_file)) },
                onClick = {
                    onDismiss()
                    onRequestDelete(true)
                },
            )
        }
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
            text = { Text(stringResource(R.string.downloads_action_remove)) },
            onClick = {
                onDismiss()
                if (hasLocalFile) {
                    onRequestDelete(false)
                } else {
                    actions.onRemove(item.id, false)
                }
            },
        )
    }
}

@Composable
private fun DownloadProgressSection(
    item: DownloadItem,
    onResumeMetered: (id: String, allowMeteredOverride: Boolean) -> Unit,
) {
    when (item.status) {
        DownloadStatus.RUNNING -> {
            val fraction = item.progressFraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        DownloadStatus.PAUSED -> {
            item.progressFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = TRACK_ALPHA),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        DownloadStatus.WAITING_FOR_WIFI -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = stringResource(R.string.downloads_status_waiting_wifi),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                TextButton(onClick = { onResumeMetered(item.id, true) }) {
                    Text(stringResource(R.string.downloads_action_download_now))
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun DeleteDownloadConfirmDialog(
    item: DownloadItem,
    defaultDeleteDisk: Boolean,
    onConfirm: (deleteFile: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val canDeleteDisk = item.status == DownloadStatus.COMPLETED && !item.fileMissing
    var deleteDisk by remember(item.id, defaultDeleteDisk) {
        mutableStateOf(defaultDeleteDisk && canDeleteDisk)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_delete_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.downloads_delete_confirm_body, item.fileName))
                if (canDeleteDisk) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { deleteDisk = !deleteDisk },
                    ) {
                        Checkbox(checked = deleteDisk, onCheckedChange = { deleteDisk = it })
                        Text(
                            text = stringResource(R.string.downloads_delete_also_disk),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteDisk) }) {
                Text(stringResource(R.string.action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ClearFinishedConfirmDialog(
    canDeleteFiles: Boolean,
    onConfirm: (deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_clear_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.downloads_clear_confirm_body))
                if (canDeleteFiles) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { deleteFiles = !deleteFiles },
                    ) {
                        Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                        Text(
                            text = stringResource(R.string.downloads_delete_also_disk),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteFiles) }) {
                Text(stringResource(R.string.action_clear))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun downloadSecondaryLine(item: DownloadItem): String {
    val parts =
        when (item.status) {
            DownloadStatus.RUNNING -> runningSecondaryParts(item)
            DownloadStatus.QUEUED ->
                listOfNotNull(
                    stringResource(R.string.downloads_status_queued),
                    if (item.totalBytes > 0L) formatByteSize(item.totalBytes) else null,
                )
            DownloadStatus.PAUSED ->
                listOfNotNull(
                    stringResource(R.string.downloads_status_paused),
                    progressBytesSummary(item).ifEmpty { null },
                )
            DownloadStatus.WAITING_FOR_WIFI ->
                listOf(
                    if (item.totalBytes > 0L) {
                        formatByteSize(item.totalBytes)
                    } else {
                        stringResource(R.string.downloads_status_waiting_wifi)
                    },
                )
            DownloadStatus.COMPLETED -> completedSecondaryParts(item)
            DownloadStatus.FAILED -> listOf(failureExplanation(item))
            DownloadStatus.CANCELED -> listOf(stringResource(R.string.downloads_status_canceled))
        }
    return parts.filter { it.isNotBlank() }.joinToString(" • ")
}

@Composable
private fun runningSecondaryParts(item: DownloadItem): List<String> =
    listOfNotNull(
        progressBytesSummary(item).ifEmpty { null },
        if (item.speedBytesPerSec > 0L) "${formatByteSize(item.speedBytesPerSec)}/s" else null,
        item.etaSeconds?.let { formatEta(it) },
    )

@Composable
private fun completedSecondaryParts(item: DownloadItem): List<String> {
    if (item.fileMissing) {
        return listOf(stringResource(R.string.downloads_file_missing))
    }
    val finalSize = if (item.totalBytes > 0L) item.totalBytes else item.bytesDownloaded
    val ts = item.completedAtEpochMillis ?: item.updatedAtEpochMillis
    return listOfNotNull(
        if (finalSize > 0L) formatByteSize(finalSize) else null,
        DateUtils.getRelativeTimeSpanString(ts).toString(),
    )
}

private fun progressBytesSummary(item: DownloadItem): String =
    when {
        item.totalBytes > 0L ->
            "${formatByteSize(item.bytesDownloaded)} / ${formatByteSize(item.totalBytes)}"
        item.bytesDownloaded > 0L -> formatByteSize(item.bytesDownloaded)
        else -> ""
    }

@Composable
private fun formatEta(seconds: Long): String =
    when (val parts = etaParts(seconds)) {
        is EtaParts.Seconds -> stringResource(R.string.downloads_eta_sec_fmt, parts.seconds)
        is EtaParts.Minutes -> stringResource(R.string.downloads_eta_min_fmt, parts.minutes, parts.seconds)
        is EtaParts.Hours -> stringResource(R.string.downloads_eta_hour_fmt, parts.hours, parts.minutes)
    }

@Composable
private fun failureExplanation(item: DownloadItem): String =
    when (item.failureReason) {
        DownloadFailureReason.NETWORK_ERROR -> stringResource(R.string.downloads_reason_network)
        DownloadFailureReason.HTTP_ERROR ->
            item.httpStatusCode?.let { stringResource(R.string.downloads_reason_http_fmt, it) }
                ?: stringResource(R.string.downloads_status_failed)
        DownloadFailureReason.STORAGE_ERROR -> stringResource(R.string.downloads_reason_storage)
        DownloadFailureReason.INVALID_URL -> stringResource(R.string.downloads_reason_invalid_url)
        DownloadFailureReason.JS_DISABLED -> stringResource(R.string.downloads_reason_js_disabled)
        DownloadFailureReason.BLOB_ERROR -> stringResource(R.string.downloads_reason_blob)
        DownloadFailureReason.USER_CANCELED -> stringResource(R.string.downloads_reason_canceled)
        DownloadFailureReason.NONE,
        DownloadFailureReason.UNKNOWN,
        -> item.failureDetail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.downloads_reason_unknown)
    }

private fun fileTypeIcon(item: DownloadItem): ImageVector {
    if (fileExtensionOf(item.fileName) in APK_EXTENSIONS) {
        return Icons.Default.Android
    }
    return when (categorizeDownload(item.fileName, item.mimeType)) {
        DownloadCategory.IMAGES -> Icons.Default.Image
        DownloadCategory.VIDEOS -> Icons.Default.Movie
        DownloadCategory.AUDIO -> Icons.Default.AudioFile
        DownloadCategory.DOCUMENTS -> Icons.Default.Description
        DownloadCategory.ARCHIVES_AND_APPS -> Icons.Default.FolderZip
        else ->
            if (item.status == DownloadStatus.COMPLETED) {
                Icons.Default.DownloadDone
            } else {
                Icons.AutoMirrored.Filled.InsertDriveFile
            }
    }
}

private fun categoryLabelRes(category: DownloadCategory): Int =
    when (category) {
        DownloadCategory.ALL -> R.string.downloads_filter_all
        DownloadCategory.ACTIVE -> R.string.downloads_filter_active
        DownloadCategory.COMPLETED -> R.string.downloads_filter_completed
        DownloadCategory.IMAGES -> R.string.downloads_filter_images
        DownloadCategory.VIDEOS -> R.string.downloads_filter_videos
        DownloadCategory.AUDIO -> R.string.downloads_filter_audio
        DownloadCategory.DOCUMENTS -> R.string.downloads_filter_documents
        DownloadCategory.ARCHIVES_AND_APPS -> R.string.downloads_filter_archives_apps
        DownloadCategory.FAILED -> R.string.downloads_filter_failed
    }
