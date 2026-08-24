@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.custom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy

@Composable
fun CustomServicesScreen(
    routes: List<Route>,
    message: String?,
    onBack: () -> Unit,
    onAdd: (Route) -> Unit,
    onRemove: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom services") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CustomServiceForm(onAdd = onAdd)
                message?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            items(routes, key = Route::id) { route ->
                ListItem(
                    headlineContent = { Text(route.name) },
                    supportingContent = { Text(route.hosts.joinToString(", ")) },
                    trailingContent = {
                        TextButton(onClick = { onRemove(route.id) }) { Text("Remove") }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CustomServiceForm(onAdd: (Route) -> Unit) {
    var name by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf("") }
    var frontendName by remember { mutableStateOf("") }
    var instance by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Add a service")
        Text(
            "Use one hostname per line. A template is optional and supports " +
                "{instance}, {path}, {query:name}, and {fragment}.",
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Service name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hosts,
            onValueChange = { hosts = it },
            label = { Text("Source hostnames") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = frontendName,
            onValueChange = { frontendName = it },
            label = { Text("Frontend name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = instance,
            onValueChange = { instance = it },
            label = { Text("HTTPS instance origin") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = template,
            onValueChange = { template = it },
            label = { Text("Optional output template") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val cleanName = name.trim()
                val cleanHosts = hosts.split(',', '\n', '\r', ' ', '\t').map(String::trim).filter(String::isNotEmpty)
                val cleanInstance = instance.trim()
                if (cleanName.isNotEmpty() && cleanHosts.isNotEmpty() && cleanInstance.isNotEmpty()) {
                    val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    val id = "custom-$slug-${System.currentTimeMillis()}"
                    val strategy =
                        template.trim().takeIf(String::isNotEmpty)?.let { output -> Strategy.Template(output) }
                            ?: Strategy.ReplaceOrigin
                    onAdd(
                        Route(
                            id = id,
                            name = cleanName,
                            hosts = cleanHosts.distinct().map(String::lowercase),
                            frontends =
                                listOf(
                                    Frontend(
                                        id = "custom-frontend",
                                        name = frontendName.trim().ifEmpty { "Custom frontend" },
                                        strategy = strategy,
                                        instances = listOf(cleanInstance),
                                    ),
                                ),
                        ),
                    )
                    name = ""
                    hosts = ""
                    frontendName = ""
                    instance = ""
                    template = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add service")
        }
    }
}
