@file:OptIn(ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.routing.TraceReason
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.redirect.LinkInspection
import dev.libredirect.mobile.settings.OriginalFallback
import dev.libredirect.mobile.ui.settings.SettingsChoice

@Suppress("CyclomaticComplexMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinkInspectionScreen(
    text: String,
    inspect: suspend (String) -> LinkInspection,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val matches = remember(text) { UrlCandidate.matches(text).toList() }
    var results by remember(text) { mutableStateOf<List<LinkInspection>>(emptyList()) }
    var oneTimeTarget by remember(text) { mutableStateOf<String?>(null) }
    var excluded by remember(text) { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(text) {
        results = matches.map { inspect(it.url) }
    }

    fun copy(value: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.inspect_title), value))
    }

    fun share(value: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        context.startActivity(Intent.createChooser(intent, null))
    }

    fun open(
        result: LinkInspection,
        original: Boolean,
    ) {
        val url = if (original) result.original else result.destination ?: return
        if (UrlParser.parse(url)?.scheme !in setOf("http", "https")) return
        if (!BrowserLauncher(context).launch(
                context,
                url,
                oneTimeTarget ?: result.targetPackage,
                result.redirectInfo().takeUnless {
                    original
                },
            )
        ) {
            Toast.makeText(context, R.string.redirect_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inspect_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (matches.isEmpty()) item { Text(stringResource(R.string.home_quick_link_error)) }
            if (matches.isNotEmpty() && results.isEmpty()) item { Text(stringResource(R.string.inspect_loading)) }
            itemsIndexed(results) { index, result ->
                if (results.size == 1) {
                    LinkDecision(result)
                    val launchUrl = result.destination ?: result.original
                    val targets = remember(launchUrl) { BrowserLauncher(context).installedTargets(launchUrl) }
                    val choices = mutableMapOf<String?, String>(null to stringResource(R.string.link_target_default))
                    targets.forEach { choices[it.packageName] = it.label }
                    choices[BrowserLauncher.ASK_PACKAGE] = stringResource(R.string.link_target_ask)
                    SettingsChoice(stringResource(R.string.inspect_one_time_target), choices, oneTimeTarget) {
                        oneTimeTarget = it
                    }
                    FlowRow {
                        TextButton(onClick = { copy(result.cleaned) }) { Text(stringResource(R.string.inspect_copy)) }
                        TextButton(onClick = { share(result.cleaned) }) { Text(stringResource(R.string.inspect_share)) }
                        TextButton(onClick = { open(result, false) }, enabled = result.destination != null) {
                            Text(stringResource(R.string.inspect_open))
                        }
                        TextButton(
                            onClick = { open(result, true) },
                        ) { Text(stringResource(R.string.inspect_unmodified)) }
                    }
                } else {
                    ListItem(
                        headlineContent = { Text(result.original, maxLines = 2) },
                        supportingContent = { Text(result.cleaned, maxLines = 2) },
                        leadingContent = {
                            Checkbox(
                                checked = index !in excluded,
                                onCheckedChange = { checked ->
                                    excluded = if (checked) excluded - index else excluded + index
                                },
                            )
                        },
                    )
                }
            }
            if (results.size > 1) {
                item {
                    fun cleanedText(): String =
                        matches.indices.reversed().fold(text) { value, index ->
                            if (index in excluded || results[index].cleaned == results[index].original) {
                                value
                            } else {
                                value.replaceRange(matches[index].range, results[index].cleaned)
                            }
                        }

                    FlowRow {
                        TextButton(onClick = { copy(cleanedText()) }) { Text(stringResource(R.string.inspect_copy)) }
                        TextButton(onClick = { share(cleanedText()) }) { Text(stringResource(R.string.inspect_share)) }
                    }
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun LinkDecision(result: LinkInspection) {
    val trace = result.trace
    val decision =
        when (trace.reason) {
            TraceReason.NONE -> R.string.inspect_redirect
            TraceReason.NO_ROUTE -> R.string.inspect_no_route
            TraceReason.NO_INSTANCE, TraceReason.INSTANCE_COOLDOWN -> R.string.inspect_no_instance
            TraceReason.UNSUPPORTED_LINK_SHAPE -> R.string.inspect_unsupported
            TraceReason.SIGNED_QUERY -> R.string.inspect_signed
            TraceReason.EXCEPTION_MATCHED -> R.string.inspect_exception
            TraceReason.ROUTE_DISABLED, TraceReason.ROUTING_DISABLED -> R.string.inspect_disabled
            TraceReason.PAUSED -> R.string.home_routing_paused
            TraceReason.NOT_ELIGIBLE -> R.string.home_quick_link_error
            TraceReason.STRATEGY_PASSTHROUGH, TraceReason.TARGET_UNCHANGED -> R.string.inspect_unmodified
        }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(decision), style = MaterialTheme.typography.titleMedium)
            InspectionValue(R.string.inspect_original, result.original)
            InspectionValue(R.string.inspect_cleaned, result.cleaned)
            result.destination?.takeIf { it != result.cleaned }?.let {
                InspectionValue(
                    R.string.inspect_destination,
                    it,
                )
            }
            trace.routeId?.let {
                InspectionValue(
                    R.string.inspect_rule,
                    listOfNotNull(it, trace.frontendId).joinToString(" / "),
                )
            }
            result.targetPackage?.let { InspectionValue(R.string.inspect_target, it) }
            if (result.fallback == OriginalFallback.BLOCK) {
                Text(stringResource(R.string.original_fallback_blocked))
            }
            if (result.removedParameters.isNotEmpty()) {
                InspectionValue(R.string.inspect_removed_names, result.removedParameters.joinToString(", "))
            }
            if (trace.strippedParams > 0 || trace.unwrappedLayers > 0) {
                Text(stringResource(R.string.inspect_steps, trace.strippedParams, trace.unwrappedLayers))
            }
        }
    }
}

@Composable
private fun InspectionValue(
    label: Int,
    value: String,
) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}
