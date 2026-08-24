@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.exceptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.core.routing.ExceptionRule

@Composable
fun ExceptionsScreen(
    exceptions: List<ExceptionRule>,
    onBack: () -> Unit,
    onExceptionsChange: (List<ExceptionRule>) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exceptions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxWidth(),
        ) {
            item { AddExceptionRow(onAdd = { rule -> onExceptionsChange(exceptions + rule) }) }
            item { HorizontalDivider() }

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
            label = { Text("example.com or example.com/path") },
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
            Text("Add")
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
        }
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { TextButton(onClick = onRemove) { Text("Remove") } },
    )
}
