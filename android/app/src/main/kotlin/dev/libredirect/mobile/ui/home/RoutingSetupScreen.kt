@file:OptIn(ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.home

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.libredirect.mobile.R
import dev.libredirect.mobile.redirect.SetupCheck
import dev.libredirect.mobile.ui.settings.BrowserRoleHost

@Suppress("CyclomaticComplexMethod")
@Composable
internal fun RoutingSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stage by SetupCheck.stage.collectAsState()
    var defaultBrowser by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    defaultBrowser =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.getSystemService(RoleManager::class.java)
                            ?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
                        } else {
                            context.packageManager.resolveActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(SetupCheck.SAMPLE_URL)), 0,
                            )?.activityInfo?.packageName == context.packageName
                        }
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.setup_role), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (defaultBrowser) R.string.setup_default_active else R.string.setup_default_inactive))
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context is BrowserRoleHost) {
                    context.requestBrowserRole()
                } else {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
            }) { Text(stringResource(R.string.setup_request_role)) }
            Text(stringResource(R.string.setup_defaults), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.setup_defaults_help))
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }) {
                Text(stringResource(R.string.setup_open_defaults))
            }
            TextButton(onClick = {
                SetupCheck.start()
                val opened =
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SetupCheck.SAMPLE_URL)))
                    }.isSuccess
                if (!opened) SetupCheck.record(SetupCheck.SAMPLE_URL, SetupCheck.Stage.FAILED)
            }) { Text(stringResource(R.string.setup_try_link)) }
            Text(stringResource(R.string.setup_result), style = MaterialTheme.typography.titleMedium)
            if (stage >= SetupCheck.Stage.RECEIVED && stage != SetupCheck.Stage.FAILED) {
                Text(stringResource(R.string.setup_received))
            }
            if (stage >= SetupCheck.Stage.RESOLVED && stage != SetupCheck.Stage.FAILED) {
                Text(stringResource(R.string.setup_resolved))
            }
            when (stage) {
                SetupCheck.Stage.WAITING -> Text(stringResource(R.string.setup_waiting))
                SetupCheck.Stage.CHOOSER -> Text(stringResource(R.string.setup_chooser))
                SetupCheck.Stage.DELIVERED -> Text(stringResource(R.string.setup_delivered))
                SetupCheck.Stage.FAILED -> Text(stringResource(R.string.setup_failed))
                else -> Unit
            }
        }
    }
}
