package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R

@Composable
fun SearchEngineDialog(
    currentEngine: SearchEngine,
    customEngine: SearchEngine?,
    onSelectEngine: (SearchEngine) -> Unit,
    onSaveCustom: (name: String, template: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var isCustomFormVisible by remember { mutableStateOf(false) }

    if (isCustomFormVisible) {
        CustomSearchEngineDialog(
            initialName = customEngine?.displayName.orEmpty(),
            initialTemplate = customEngine?.queryTemplate.orEmpty(),
            onSave = { name, template ->
                isCustomFormVisible = false
                onSaveCustom(name, template)
                onDismiss()
            },
            onDismiss = { isCustomFormVisible = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.browser_search_engine_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SearchEngine.BUILT_INS.forEach { engine ->
                    SearchEngineRow(
                        engine = engine,
                        isSelected = engine == currentEngine,
                        onSelect = {
                            onSelectEngine(engine)
                            onDismiss()
                        },
                    )
                }
                customEngine?.let { custom ->
                    SearchEngineRow(
                        engine = custom,
                        isSelected = currentEngine.id == SearchEngine.CUSTOM_ID,
                        onSelect = {
                            onSelectEngine(custom)
                            onDismiss()
                        },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SearchEngineActionRow(
                    icon = Icons.Default.Edit,
                    text =
                        stringResource(
                            if (customEngine == null) {
                                R.string.browser_search_engine_add_custom
                            } else {
                                R.string.browser_search_engine_edit_custom
                            },
                        ),
                    onClick = { isCustomFormVisible = true },
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
private fun SearchEngineRow(
    engine: SearchEngine,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
        )
        Column {
            Text(
                text = engine.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = engine.homeUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchEngineActionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}
