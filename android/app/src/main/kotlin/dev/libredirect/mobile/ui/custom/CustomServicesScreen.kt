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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                val instance = route.frontends.firstOrNull()?.instances?.firstOrNull()
                ListItem(
                    headlineContent = { Text(route.name) },
                    supportingContent = {
                        Column {
                            Text(route.hosts.joinToString(", "))
                            if (instance != null) {
                                Text(
                                    text = "→ $instance",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
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
        Text(
            text = stringResource(R.string.custom_services_add_header),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.custom_services_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader(stringResource(R.string.custom_services_section_links))
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

/** Small step heading that groups the form into an answerable question per step. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Every field carries a permanent format hint in its supporting text, so the
 * form is understandable without prior knowledge; after a submit attempt an
 * error replaces the hint on the offending fields.
 */
@Composable
private fun CustomServiceFields(
    draft: CustomServiceDraft,
    validation: DraftValidation,
    showErrors: Boolean,
    onDraftChange: (CustomServiceDraft) -> Unit,
) {
    Field(
        value = draft.name,
        onValueChange = { onDraftChange(draft.copy(name = it)) },
        labelRes = R.string.custom_services_field_name,
        hintRes = R.string.custom_services_hint_name,
        errorRes = R.string.custom_services_error_name,
        isError = showErrors && validation.nameError,
    )
    Field(
        value = draft.hosts,
        onValueChange = { onDraftChange(draft.copy(hosts = it)) },
        labelRes = R.string.custom_services_field_hosts,
        hintRes = R.string.custom_services_hint_hosts,
        errorRes = R.string.custom_services_error_hosts,
        isError = showErrors && validation.hostsError,
        minLines = 2,
    )

    SectionHeader(stringResource(R.string.custom_services_section_destination))
    Field(
        value = draft.frontendName,
        onValueChange = { onDraftChange(draft.copy(frontendName = it)) },
        labelRes = R.string.custom_services_field_frontend,
        hintRes = R.string.custom_services_hint_frontend,
        errorRes = null,
        isError = false,
    )
    Field(
        value = draft.instance,
        onValueChange = { onDraftChange(draft.copy(instance = it)) },
        labelRes = R.string.custom_services_field_instance,
        hintRes = R.string.custom_services_hint_instance,
        errorRes = R.string.custom_services_error_instance,
        isError = showErrors && validation.instanceError,
    )

    SectionHeader(stringResource(R.string.custom_services_section_template))
    Field(
        value = draft.template,
        onValueChange = { onDraftChange(draft.copy(template = it)) },
        labelRes = R.string.custom_services_field_template,
        hintRes = R.string.custom_services_hint_template,
        errorRes = R.string.custom_services_error_template,
        isError = showErrors && validation.templateError,
    )
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    hintRes: Int,
    errorRes: Int?,
    isError: Boolean,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        supportingText = {
            val textRes = if (isError && errorRes != null) errorRes else hintRes
            Text(
                text = stringResource(textRes),
                color =
                    if (isError && errorRes != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        isError = isError,
        singleLine = minLines == 1,
        minLines = minLines,
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
