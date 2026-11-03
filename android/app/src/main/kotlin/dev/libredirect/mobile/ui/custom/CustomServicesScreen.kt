@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.custom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.url.UrlValidation

private data class CustomServiceDraft(
    val name: String = "",
    val hosts: String = "",
    val frontendName: String = "",
    val instance: String = "",
    val template: String = "",
)

/** Field-level validation results for a draft; errors render only after a submit attempt. */
private data class DraftValidation(
    val nameError: Boolean,
    val hostsError: Boolean,
    val instanceError: Boolean,
    val templateError: Boolean,
) {
    val isValid: Boolean get() = !nameError && !hostsError && !instanceError && !templateError
}

private fun validateDraft(draft: CustomServiceDraft): DraftValidation {
    val cleanHosts = draft.hosts.split(',', '\n', '\r', ' ', '\t').map(String::trim).filter(String::isNotEmpty)
    val cleanInstance = draft.instance.trim().lowercase()
    val cleanTemplate = draft.template.trim()
    return DraftValidation(
        nameError = draft.name.isBlank(),
        hostsError = cleanHosts.isEmpty(),
        instanceError = !UrlValidation.isValidHttpsOrigin(cleanInstance),
        templateError = cleanTemplate.isNotEmpty() && hasUnsupportedPlaceholders(cleanTemplate),
    )
}

/** Mirrors the manifest validator's template placeholder grammar check. */
private fun hasUnsupportedPlaceholders(template: String): Boolean =
    template
        .replace(Regex("\\{(instance|path|fragment|query:[A-Za-z0-9_]+)\\}"), "")
        .any { it == '{' || it == '}' }

@Composable
fun CustomServicesScreen(
    routes: List<Route>,
    message: String?,
    onBack: () -> Unit,
    onAdd: (Route) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_services_title)) },
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
                        TextButton(onClick = { onRemove(route.id) }) {
                            Text(stringResource(R.string.action_remove))
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CustomServiceForm(onAdd: (Route) -> Unit) {
    var draft by remember { mutableStateOf(CustomServiceDraft()) }
    var showErrors by remember { mutableStateOf(false) }
    val validation = remember(draft) { validateDraft(draft) }
    val defaultFrontendName = stringResource(R.string.custom_services_default_frontend)

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.custom_services_add_header))
        Text(stringResource(R.string.custom_services_instructions))
        CustomServiceFields(
            draft = draft,
            validation = validation,
            showErrors = showErrors,
            onDraftChange = { draft = it },
        )
        Button(
            onClick = {
                if (validation.isValid) {
                    draft
                        .toRoute(defaultFrontendName = defaultFrontendName)
                        ?.let {
                            onAdd(it)
                            draft = CustomServiceDraft()
                            showErrors = false
                        }
                } else {
                    showErrors = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.custom_services_btn_add))
        }
    }
}

@Composable
private fun CustomServiceFields(
    draft: CustomServiceDraft,
    validation: DraftValidation,
    showErrors: Boolean,
    onDraftChange: (CustomServiceDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.name,
        onValueChange = { onDraftChange(draft.copy(name = it)) },
        label = { Text(stringResource(R.string.custom_services_field_name)) },
        isError = showErrors && validation.nameError,
        supportingText = {
            if (showErrors && validation.nameError) {
                Text(stringResource(R.string.custom_services_error_name))
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.hosts,
        onValueChange = { onDraftChange(draft.copy(hosts = it)) },
        label = { Text(stringResource(R.string.custom_services_field_hosts)) },
        isError = showErrors && validation.hostsError,
        supportingText = {
            if (showErrors && validation.hostsError) {
                Text(stringResource(R.string.custom_services_error_hosts))
            }
        },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.frontendName,
        onValueChange = { onDraftChange(draft.copy(frontendName = it)) },
        label = { Text(stringResource(R.string.custom_services_field_frontend)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.instance,
        onValueChange = { onDraftChange(draft.copy(instance = it)) },
        label = { Text(stringResource(R.string.custom_services_field_instance)) },
        isError = showErrors && validation.instanceError,
        supportingText = {
            if (showErrors && validation.instanceError) {
                Text(stringResource(R.string.custom_services_error_instance))
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.template,
        onValueChange = { onDraftChange(draft.copy(template = it)) },
        label = { Text(stringResource(R.string.custom_services_field_template)) },
        isError = showErrors && validation.templateError,
        supportingText = {
            if (showErrors && validation.templateError) {
                Text(stringResource(R.string.custom_services_error_template))
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun CustomServiceDraft.toRoute(defaultFrontendName: String): Route? {
    val cleanName = name.trim()
    val cleanHosts = hosts.split(',', '\n', '\r', ' ', '\t').map(String::trim).filter(String::isNotEmpty)
    val cleanInstance = instance.trim().lowercase()
    if (cleanName.isEmpty() || cleanHosts.isEmpty() || !UrlValidation.isValidHttpsOrigin(cleanInstance)) {
        return null
    }
    val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val strategy = template.trim().takeIf(String::isNotEmpty)?.let(Strategy::Template) ?: Strategy.ReplaceOrigin
    return Route(
        id = "custom-$slug-${System.currentTimeMillis()}",
        name = cleanName,
        hosts = cleanHosts.distinct().map(String::lowercase),
        frontends =
            listOf(
                Frontend(
                    id = "custom-frontend",
                    name = frontendName.trim().ifEmpty { defaultFrontendName },
                    strategy = strategy,
                    instances = listOf(cleanInstance),
                ),
            ),
    )
}
