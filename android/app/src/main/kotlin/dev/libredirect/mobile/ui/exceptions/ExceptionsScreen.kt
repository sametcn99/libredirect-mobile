@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.exceptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.core.routing.ExceptionRule

@Composable
fun ExceptionsScreen(
    exceptions: List<ExceptionRule>,
    onBack: () -> Unit,
    onExceptionsChange: (List<ExceptionRule>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exceptions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            item { AddExceptionRow(onAdd = { rule -> onExceptionsChange(exceptions + rule) }) }
            item { HorizontalDivider() }

            // 2.5: exclude links that arrive from a given app (referrer).
            item {
                SourceAppSection(
                    installedPackages = queryLaunchablePackages(context),
                    onAdd = { rule -> onExceptionsChange(exceptions + rule) },
                )
                HorizontalDivider()
            }

            items(exceptions) { rule ->
                ExceptionRow(
                    rule = rule,
                    onRemove = { onExceptionsChange(exceptions - rule) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Launchable packages via the manifest's <queries> launcher intent — never QUERY_ALL_PACKAGES. */
private fun queryLaunchablePackages(context: android.content.Context): List<String> {
    val intent =
        android.content.Intent(
            android.content.Intent.ACTION_MAIN,
        ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return try {
        context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }
}

/** 2.5: pick an installed app (or type a package name) whose links skip routing. */
@Composable
private fun SourceAppSection(
    installedPackages: List<String>,
    onAdd: (ExceptionRule) -> Unit,
) {
    var isPickerVisible by remember { mutableStateOf(false) }
    var manualPackage by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.exceptions_source_app_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualPackage,
                onValueChange = { manualPackage = it },
                label = { Text(stringResource(R.string.exceptions_source_app_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (manualPackage.isNotBlank()) {
                        onAdd(ExceptionRule.SourceApp(manualPackage.trim()))
                        manualPackage = ""
                    }
                },
                enabled = manualPackage.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_add))
            }
        }
        TextButton(onClick = { isPickerVisible = true }) {
            Text(stringResource(R.string.exceptions_source_app_pick))
        }
        Text(
            text = stringResource(R.string.exceptions_source_app_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.exceptions_wildcard_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (isPickerVisible) {
        AlertDialog(
            onDismissRequest = { isPickerVisible = false },
            title = { Text(stringResource(R.string.exceptions_source_app_pick)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(installedPackages) { packageName ->
                        ListItem(
                            headlineContent = { Text(packageName) },
                            modifier =
                                Modifier.clickable {
                                    onAdd(ExceptionRule.SourceApp(packageName))
                                    isPickerVisible = false
                                },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { isPickerVisible = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun AddExceptionRow(onAdd: (ExceptionRule) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.exceptions_input_placeholder)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                onAdd(if (trimmed.contains('/')) ExceptionRule.UrlPrefix(trimmed) else ExceptionRule.Domain(trimmed))
                text = ""
            }
        }) {
            Text(stringResource(R.string.action_add))
        }
    }
}

@Composable
private fun ExceptionRow(
    rule: ExceptionRule,
    onRemove: () -> Unit,
) {
    val label =
        when (rule) {
            is ExceptionRule.Domain -> rule.host
            is ExceptionRule.UrlPrefix -> rule.value
            is ExceptionRule.SourceApp -> rule.packageName
        }
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.action_remove))
            }
        },
    )
}
