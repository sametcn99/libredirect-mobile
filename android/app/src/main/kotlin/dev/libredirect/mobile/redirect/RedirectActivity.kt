package dev.libredirect.mobile.redirect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.browser.InAppRedirectInfo
import dev.libredirect.mobile.core.routing.RoutingFailure
import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.routing.UrlRouter
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * No UI (see the NoDisplay theme in AndroidManifest.xml): this activity
 * exists only to compute a destination and hand off to [BrowserLauncher]
 * before finishing, per the project plan's performance target
 * (route resolution should not block on drawing anything).
 */
class RedirectActivity : ComponentActivity() {
    @Suppress("TooGenericExceptionCaught")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as LibRedirectApplication
        // Route on the application scope, not the activity's: this screen is
        // invisible and finishes (or is torn down) as soon as the user moves
        // on — a lifecycle-scoped coroutine would be cancelled mid-route and
        // silently drop the link.
        app.applicationScope.launch {
            try {
                val incomingUrl = extractUrl(intent)
                if (incomingUrl != null) route(app, incomingUrl)
            } catch (_: CancellationException) {
                // Scope cancellation means the process is going away anyway.
            } catch (error: Exception) {
                Log.e(TAG, "Could not route incoming URL", error)
                showMessage(app, R.string.redirect_cannot_open)
            } finally {
                // A no-op when the activity is already gone.
                finish()
            }
        }
    }

    private fun extractUrl(intent: Intent): String? =
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> extractFromSharedText(intent)
            else -> null
        }

    /**
     * Shared-text extraction lives in the pure [UrlCandidate] (JVM-tested); it adds
     * an https scheme to bare domains.
     */
    private fun extractFromSharedText(intent: Intent): String? =
        intent
            .takeIf { it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let(UrlCandidate::find)

    private suspend fun route(
        app: LibRedirectApplication,
        url: String,
    ) {
        val manifest = withContext(Dispatchers.IO) { app.manifestRepository.activeManifest() }
        val settings = SettingsRepository(app).settings.first()

        val result =
            if (!settings.routingEnabled) {
                RoutingResult.Passthrough(url)
            } else if (manifest != null) {
                UrlRouter(manifest).resolve(url, settings.routingContext())
            } else {
                RoutingResult.Passthrough(url)
            }

        val destination = destinationFor(result) ?: return showMessage(app, R.string.redirect_cannot_open)

        val redirectInfo =
            (result as? RoutingResult.Redirect)?.let {
                InAppRedirectInfo(
                    originalUrl = it.originalUrl,
                    routeId = it.routeId,
                    frontendId = it.frontendId,
                    instance = it.instance,
                )
            }
        // Launch from the application context: by the time routing finishes
        // the activity may already be destroyed.
        val launcher = BrowserLauncher(app)
        val launched =
            launcher.launch(
                app,
                destination,
                preferredPackage = settings.selectedBrowserPackage,
                redirect = redirectInfo,
            )
        if (!launched) {
            showMessage(app, R.string.redirect_no_browser)
        }
    }

    /**
     * Null means the input was never eligible to open at all (bad scheme /
     * unparseable) — see [RoutingFailure]'s documentation for why that must
     * not fail-open the same way [RoutingFailure.NO_INSTANCE_AVAILABLE] does.
     */
    private fun destinationFor(result: RoutingResult): String? =
        when (result) {
            is RoutingResult.Redirect -> result.redirectedUrl
            is RoutingResult.Passthrough -> result.url
            is RoutingResult.Failure ->
                when (result.reason) {
                    RoutingFailure.NO_INSTANCE_AVAILABLE -> result.url
                    RoutingFailure.MALFORMED_URL, RoutingFailure.UNSUPPORTED_SCHEME -> null
                }
        }

    private fun showMessage(
        context: Context,
        messageRes: Int,
    ) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "RedirectActivity"
    }
}
