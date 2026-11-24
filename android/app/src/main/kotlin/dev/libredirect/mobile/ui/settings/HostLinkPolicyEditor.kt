package dev.libredirect.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.settings.HostLinkPolicy

private val domainPattern = Regex("^[a-z0-9-]+(?:\\.[a-z0-9-]+)+$")

@Composable
internal fun HostLinkPolicyEditor(
    policies: Map<String, HostLinkPolicy>,
    onSave: (String, HostLinkPolicy) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    Column {
        policies.keys.sorted().forEach { host ->
            TextButton(onClick = { editing = host }) { Text(host) }
        }
        TextButton(onClick = { editing = "" }) { Text(stringResource(R.string.host_link_rules)) }
    }
    editing?.let { host ->
        HostLinkPolicyDialog(host, policies[host] ?: HostLinkPolicy(), onDismiss = { editing = null }) { key, policy ->
            onSave(key, policy)
            editing = null
        }
    }
}

@Composable
private fun HostLinkPolicyDialog(
    initialHost: String,
    initial: HostLinkPolicy,
    onDismiss: () -> Unit,
    onSave: (String, HostLinkPolicy) -> Unit,
) {
    val context = LocalContext.current
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var names by remember(initialHost) { mutableStateOf(initial.preserveParameters.joinToString(", ")) }
    var policy by remember(initialHost) { mutableStateOf(initial) }
    val key = host.trim().lowercase().trimEnd('.')
    val valid = domainPattern.matches(key)
    val targets =
        remember(key.takeIf { valid }) {
            val launcher = BrowserLauncher(context)
            if (valid) launcher.installedTargets("https://$key/") else launcher.installedBrowsers()
        }
    val choices =
        mutableMapOf<String?, String>(
            null to stringResource(R.string.link_target_default),
            BrowserLauncher.ASK_PACKAGE to stringResource(R.string.link_target_ask),
        )
    targets.forEach { target -> choices[target.packageName] = target.label }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.host_link_rules)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    readOnly = initialHost.isNotEmpty(),
                    label = { Text(stringResource(R.string.host_link_domain)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = names,
                    onValueChange = { names = it },
                    label = { Text(stringResource(R.string.host_link_preserve)) },
                )
                val yes = stringResource(R.string.host_link_skip)
                val no = stringResource(R.string.link_target_default)
                SettingsChoice(
                    stringResource(R.string.settings_strip_query_params),
                    mapOf(false to no, true to yes),
                    policy.skipExtraCleanup,
                ) { policy = policy.copy(skipExtraCleanup = it) }
                SettingsChoice(
                    stringResource(R.string.settings_unwrap_wrappers),
                    mapOf(false to no, true to yes),
                    policy.skipWrapperUnwrap,
                ) { policy = policy.copy(skipWrapperUnwrap = it) }
                SettingsChoice(stringResource(R.string.inspect_target), choices, policy.targetPackage) {
                    policy = policy.copy(targetPackage = it)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val preserved = names.split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet()
                    onSave(key, policy.copy(preserveParameters = preserved))
                },
            ) { Text(stringResource(R.string.host_link_save)) }
        },
        dismissButton = {
            if (initialHost.isNotEmpty()) {
                TextButton(onClick = { onSave(initialHost, HostLinkPolicy()) }) {
                    Text(stringResource(R.string.host_link_remove))
                }
            }
        },
    )
}
