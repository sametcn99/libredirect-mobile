@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tab_switcher_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.tab_switcher_count_fmt, tabs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (tabs.size > 1) {
                    TextButton(onClick = actions.onCloseAll) {
                        Text(stringResource(R.string.tab_switcher_close_all))
                    }
                }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(
                    onClick = actions.onNewTab,
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tab_switcher_new_tab))
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
                items(tabs, key = BrowserTab::id) { tab ->
                    TabCard(
                        tab = tab,
                        isActive = tab.id == activeTabId,
                        onSelect = { actions.onTabSelect(tab.id) },
                        onClose = { actions.onTabClose(tab.id) },
                    )
                }
            }
        }
    }
}

data class TabSwitcherActions(
    val onTabSelect: (String) -> Unit,
    val onTabClose: (String) -> Unit,
    val onNewTab: () -> Unit,
    val onCloseAll: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
private fun TabCard(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
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
            SiteIcon(tab = tab, size = 18.dp)
            Spacer(Modifier.width(8.dp))
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
        }

        // Preview area: a live screenshot of the page as it last looked, or the
        // site's icon over its host for tabs that have never been active.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
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
                    SiteIcon(tab = tab, size = 40.dp)
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
