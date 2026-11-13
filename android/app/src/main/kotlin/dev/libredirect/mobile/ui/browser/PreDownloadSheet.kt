package dev.libredirect.mobile.ui.browser

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.downloads.DownloadSourceKind
import dev.libredirect.mobile.downloads.PendingDownloadRequest
import dev.libredirect.mobile.downloads.formatByteSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreDownloadSheet(
    request: PendingDownloadRequest,
    defaultWifiOnly: Boolean,
    askBeforeDownload: Boolean,
    onAskBeforeDownloadChange: (Boolean) -> Unit,
    onConfirm: (fileName: String, wifiOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var hasEditedName by remember(request.id) { mutableStateOf(false) }
    var fileName by remember(request.id) { mutableStateOf(request.suggestedFileName) }
    var wifiOnly by remember(request.id, defaultWifiOnly) { mutableStateOf(defaultWifiOnly) }
    LaunchedEffect(request.id, request.suggestedFileName) {
        if (!hasEditedName) {
            fileName = request.suggestedFileName
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.predownload_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (request.isPotentiallyDangerous) {
                NoticeBannerCard(
                    icon = Icons.Default.WarningAmber,
                    title = stringResource(R.string.predownload_danger_title),
                    body = stringResource(R.string.predownload_danger_body),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            if (request.isIncognito) {
                NoticeBannerCard(
                    icon = Icons.Default.Shield,
                    title = stringResource(R.string.downloads_incognito_badge),
                    body = stringResource(R.string.predownload_incognito_note),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (request.strippedTrackingCount > 0) {
                NoticeBannerCard(
                    icon = Icons.Default.VerifiedUser,
                    title = stringResource(R.string.predownload_tracking_cleaned_fmt, request.strippedTrackingCount),
                    body = null,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            OutlinedTextField(
                value = fileName,
                onValueChange = {
                    fileName = it
                    hasEditedName = true
                },
                label = { Text(stringResource(R.string.predownload_filename_label)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            MetadataSummaryCard(request = request)

            if (request.sourceKind == DownloadSourceKind.HTTP) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                    Text(
                        text = stringResource(R.string.predownload_wifi_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.predownload_ask_always),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = askBeforeDownload,
                    onCheckedChange = onAskBeforeDownloadChange,
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val finalName = fileName.trim().ifEmpty { request.suggestedFileName }
                        onConfirm(finalName, wifiOnly)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.predownload_start))
                }
            }
        }
    }
}

@Composable
private fun NoticeBannerCard(
    icon: ImageVector,
    title: String,
    body: String?,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!body.isNullOrBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataSummaryCard(request: PendingDownloadRequest) {
    val sizeText =
        if (request.contentLength > 0L) {
            formatByteSize(request.contentLength)
        } else {
            stringResource(R.string.predownload_size_unknown)
        }
    val destText =
        stringResource(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                R.string.predownload_dest_public
            } else {
                R.string.predownload_dest_private
            },
        )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetadataRow(stringResource(R.string.predownload_size_label), sizeText)
            MetadataRow(stringResource(R.string.predownload_type_label), request.mimeType)
            if (request.sourceHost.isNotBlank()) {
                MetadataRow(stringResource(R.string.predownload_source_label), request.sourceHost)
            }
            MetadataRow(stringResource(R.string.predownload_dest_label), destText)
        }
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
