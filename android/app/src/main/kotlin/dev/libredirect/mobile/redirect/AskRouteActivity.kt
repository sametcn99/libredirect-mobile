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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lightweight "ask every time" dialog (2.4): shown when a link matches a
 * service the user set to ASK mode. Offers open-in-frontend, open-original
 * and exclude-this-site; dismissing simply does not open anything — the
 * user was asked and did not choose.
 */
class AskRouteActivity : ComponentActivity() {
    private var cachedPreferredBrowser: String? = null
    private var browserPrefLoaded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val originalUrl = intent.getStringExtra(EXTRA_ORIGINAL_URL)
        val redirectedUrl = intent.getStringExtra(EXTRA_REDIRECTED_URL)
        if (originalUrl == null || redirectedUrl == null) {
            finish()
            return
        }
        val app = applicationContext as LibRedirectApplication
        if (intent.hasExtra(EXTRA_PREFERRED_BROWSER)) {
            cachedPreferredBrowser = intent.getStringExtra(EXTRA_PREFERRED_BROWSER)
            browserPrefLoaded = true
        } else {
            app.applicationScope.launch {
                try {
                    val selected = SettingsRepository(app).current().selectedBrowserPackage
                    withContext(Dispatchers.Main.immediate) {
                        cachedPreferredBrowser = selected
                        browserPrefLoaded = true
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Leave the preference unloaded: the launch falls back to
                    // the system default browser instead of crashing.
                }
            }
        }
        setContent {
            LibRedirectMobileTheme {
                AskRouteDialog(
                    originalUrl = originalUrl,
                    serviceName = intent.getStringExtra(EXTRA_SERVICE_NAME).orEmpty(),
                    frontendName = intent.getStringExtra(EXTRA_FRONTEND_NAME).orEmpty(),
                    onOpenFrontend = {
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
        if (browserPrefLoaded) {
            launchImmediately(app, url, cachedPreferredBrowser, redirect)
        } else {
            launchAfterLoadingPreference(app, url, redirect)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchImmediately(
        app: LibRedirectApplication,
        url: String,
        preferredPackage: String?,
        redirect: InAppRedirectInfo?,
    ) {
        val launched =
            try {
                BrowserLauncher(this).launch(
                    this,
                    url,
                    preferredPackage = preferredPackage,
                    redirect = redirect,
                )
            } catch (_: Exception) {
                false
            }
        finishIfAlive()
        if (!launched) {
            Toast.makeText(app, R.string.redirect_no_browser, Toast.LENGTH_SHORT).show()
        } else {
            recordRedirectAsync(app, redirect)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchAfterLoadingPreference(
        app: LibRedirectApplication,
        url: String,
        redirect: InAppRedirectInfo?,
    ) {
        app.applicationScope.launch {
            try {
                val settings = SettingsRepository(app).current()
                val launched =
                    withContext(Dispatchers.Main.immediate) {
                        val launchContext: Context = if (!isFinishing && !isDestroyed) this@AskRouteActivity else app
                        val ok =
                            BrowserLauncher(launchContext).launch(
                                launchContext,
                                url,
                                preferredPackage = settings.selectedBrowserPackage,
                                redirect = redirect,
                            )
                        finishIfAlive()
                        ok
                    }
                if (!launched) {
                    showToast(app, R.string.redirect_no_browser)
                } else {
                    recordRedirectAsync(app, redirect)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    finishIfAlive()
                    Toast.makeText(app, R.string.redirect_cannot_open, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun finishIfAlive() {
        if (!isFinishing && !isDestroyed) finish()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recordRedirectAsync(
        app: LibRedirectApplication,
        redirect: InAppRedirectInfo?,
    ) {
        val routeId = redirect?.routeId ?: return
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                BrowserDataRepository(app).recordRedirect(routeId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Counter persistence must never block or crash browser launch.
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
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
                showToast(app, R.string.ask_route_exception_added)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Nothing user-visible to recover with; the dialog is gone already.
            }
        }
    }

    private suspend fun showToast(
        app: LibRedirectApplication,
        messageRes: Int,
    ) {
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(app, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    /** See [DisplayNames]: caller-resolved names, kept out of [start]'s I/O. */
    data class DisplayNames(
        val serviceName: String,
        val frontendName: String,
    )

    companion object {
        const val EXTRA_ORIGINAL_URL = "original_url"
        const val EXTRA_REDIRECTED_URL = "redirected_url"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_FRONTEND_ID = "frontend_id"
        const val EXTRA_INSTANCE = "instance"
        const val EXTRA_SERVICE_NAME = "service_name"
        const val EXTRA_FRONTEND_NAME = "frontend_name"
        const val EXTRA_PREFERRED_BROWSER = "preferred_browser"

        fun start(
            context: Context,
            redirect: dev.libredirect.mobile.core.routing.RoutingResult.Redirect,
            displayNames: DisplayNames,
            preferredBrowserPackage: String? = null,
            hasBrowserPreference: Boolean = false,
        ) {
            val intent =
                Intent(context, AskRouteActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_ORIGINAL_URL, redirect.originalUrl)
                    putExtra(EXTRA_REDIRECTED_URL, redirect.redirectedUrl)
                    putExtra(EXTRA_ROUTE_ID, redirect.routeId)
                    putExtra(EXTRA_FRONTEND_ID, redirect.frontendId)
                    redirect.instance?.let { putExtra(EXTRA_INSTANCE, it) }
                    putExtra(EXTRA_SERVICE_NAME, displayNames.serviceName)
                    putExtra(EXTRA_FRONTEND_NAME, displayNames.frontendName)
                    if (hasBrowserPreference || preferredBrowserPackage != null) {
                        putExtra(EXTRA_PREFERRED_BROWSER, preferredBrowserPackage)
                    }
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
