@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.service

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.ui.FrontendUiState
import dev.libredirect.mobile.ui.ServiceUiState

@Composable
fun ServiceDetailScreen(
    service: ServiceUiState,
    onBack: () -> Unit,
    onFrontendSelected: (String) -> Unit,
    onInstanceSelectionChange: (frontendId: String, selection: InstanceSelection) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(service.name) },
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
            item {
                Text(
                    text = "Frontend",
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(service.frontends) { frontend ->
                FrontendRow(
                    frontend = frontend,
                    selected = frontend.id == service.selectedFrontendId,
                    onSelect = { onFrontendSelected(frontend.id) },
                )
                if (frontend.id == service.selectedFrontendId && frontend.needsInstance) {
                    InstanceSelector(
                        frontend = frontend,
                        onSelectionChange = { selection -> onInstanceSelectionChange(frontend.id, selection) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FrontendRow(
    frontend: FrontendUiState,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(frontend.name) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.clickable(onClick = onSelect),
    )
    HorizontalDivider()
}

@Composable
private fun InstanceSelector(
    frontend: FrontendUiState,
    onSelectionChange: (InstanceSelection) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        InstanceModeRow(
            label = "Automatic",
            selected = frontend.selection is InstanceSelection.Automatic,
            onClick = { onSelectionChange(InstanceSelection.Automatic) },
        )
        for (instance in frontend.instances) {
            InstanceModeRow(
                label = instance,
                selected = (frontend.selection as? InstanceSelection.Pinned)?.instance == instance,
                onClick = { onSelectionChange(InstanceSelection.Pinned(instance)) },
            )
        }

        var customText by remember {
            mutableStateOf((frontend.selection as? InstanceSelection.Custom)?.instance.orEmpty())
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = frontend.selection is InstanceSelection.Custom,
                onClick = { if (customText.isNotBlank()) onSelectionChange(InstanceSelection.Custom(customText)) },
            )
            OutlinedTextField(
                value = customText,
                onValueChange = {
                    customText = it
                    if (it.isNotBlank()) onSelectionChange(InstanceSelection.Custom(it))
                },
                label = { Text("Custom instance") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InstanceModeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
