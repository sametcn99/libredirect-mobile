package dev.libredirect.mobile.redirect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.browser.InAppRedirectInfo
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.settings.SettingsRepository
import dev.libredirect.mobile.ui.browser.BrowserDataRepository
import dev.libredirect.mobile.ui.browser.BrowserTab
import dev.libredirect.mobile.ui.theme.LibRedirectMobileTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The lightweight "ask every time" dialog (2.4): shown when a link matches a
 * service the user set to ASK mode. Offers open-in-frontend, open-original
 * and exclude-this-site; dismissing simply does not open anything — the
 * user was asked and did not choose.
 */
class AskRouteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val originalUrl = intent.getStringExtra(EXTRA_ORIGINAL_URL)
        val redirectedUrl = intent.getStringExtra(EXTRA_REDIRECTED_URL)
        if (originalUrl == null || redirectedUrl == null) {
            finish()
            return
        }
        val app = applicationContext as LibRedirectApplication
        setContent {
            LibRedirectMobileTheme {
                AskRouteDialog(
                    originalUrl = originalUrl,
                    serviceName = intent.getStringExtra(EXTRA_SERVICE_NAME).orEmpty(),
                    frontendName = intent.getStringExtra(EXTRA_FRONTEND_NAME).orEmpty(),
                    onOpenFrontend = {
                        finish()
                        val info =
                            InAppRedirectInfo(
                                originalUrl = originalUrl,
                                routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty(),
                                frontendId = intent.getStringExtra(EXTRA_FRONTEND_ID).orEmpty(),
                                instance = intent.getStringExtra(EXTRA_INSTANCE),
                            )
                        launch(app, redirectedUrl, info)
                    },
                    onOpenOriginal = {
                        finish()
                        launch(app, originalUrl, null)
                    },
                    onExclude = {
                        finish()
                        addException(app, originalUrl)
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun launch(
        app: LibRedirectApplication,
        url: String,
        redirect: InAppRedirectInfo?,
    ) {
        app.applicationScope.launch {
            try {
                val settings = SettingsRepository(app).current()
                redirect?.let { info -> BrowserDataRepository(app).recordRedirect(info.routeId) }
                val launched =
                    BrowserLauncher(app).launch(
                        app,
                        url,
                        preferredPackage = settings.selectedBrowserPackage,
                        redirect = redirect,
                    )
                if (!launched) {
                    Toast.makeText(app, R.string.redirect_no_browser, Toast.LENGTH_SHORT).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(app, R.string.redirect_cannot_open, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addException(
        app: LibRedirectApplication,
        originalUrl: String,
    ) {
        app.applicationScope.launch {
            try {
                val repository = SettingsRepository(app)
                val current = repository.current().exceptions
                val host = BrowserTab.hostOf(originalUrl)
                repository.setExceptions(current + ExceptionRule.Domain(host))
                Toast.makeText(app, R.string.ask_route_exception_added, Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Nothing user-visible to recover with; the dialog is gone already.
            }
        }
    }

    companion object {
        const val EXTRA_ORIGINAL_URL = "original_url"
        const val EXTRA_REDIRECTED_URL = "redirected_url"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_FRONTEND_ID = "frontend_id"
        const val EXTRA_INSTANCE = "instance"
        const val EXTRA_SERVICE_NAME = "service_name"
        const val EXTRA_FRONTEND_NAME = "frontend_name"

        fun start(
            context: Context,
            redirect: dev.libredirect.mobile.core.routing.RoutingResult.Redirect,
            originalUrl: String,
        ) {
            val app = context.applicationContext as LibRedirectApplication
            val route = app.manifestRepository.activeManifest()?.routes?.find { it.id == redirect.routeId }
            val frontend = route?.frontends?.find { it.id == redirect.frontendId }
            val intent =
                Intent(context, AskRouteActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_ORIGINAL_URL, originalUrl)
                    putExtra(EXTRA_REDIRECTED_URL, redirect.redirectedUrl)
                    putExtra(EXTRA_ROUTE_ID, redirect.routeId)
                    putExtra(EXTRA_FRONTEND_ID, redirect.frontendId)
                    redirect.instance?.let { putExtra(EXTRA_INSTANCE, it) }
                    putExtra(EXTRA_SERVICE_NAME, route?.name ?: redirect.routeId)
                    putExtra(EXTRA_FRONTEND_NAME, frontend?.name ?: redirect.frontendId)
                }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun AskRouteDialog(
    originalUrl: String,
    serviceName: String,
    frontendName: String,
    onOpenFrontend: () -> Unit,
    onOpenOriginal: () -> Unit,
    onExclude: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.ask_route_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text =
                        stringResource(
                            R.string.ask_route_desc_fmt,
                            serviceName.ifBlank { BrowserTab.hostOf(originalUrl) },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = originalUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                AskOptionRow(
                    icon = Icons.Default.SwapHoriz,
                    label = stringResource(R.string.ask_route_open_frontend),
                    trailing = frontendName,
                    onClick = onOpenFrontend,
                )
                AskOptionRow(
                    icon = Icons.Default.Public,
                    label = stringResource(R.string.ask_route_open_original),
                    onClick = onOpenOriginal,
                )
                AskOptionRow(
                    icon = Icons.Default.Block,
                    label = stringResource(R.string.ask_route_add_exception),
                    onClick = onExclude,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    )
}

@Composable
private fun AskOptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}
