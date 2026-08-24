package dev.libredirect.mobile.redirect

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.routing.RoutingFailure
import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.routing.UrlRouter
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val incomingUrl = extractUrl(intent)
                if (incomingUrl != null) route(incomingUrl)
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    Log.e(TAG, "Could not route incoming URL", error)
                    showMessage(R.string.redirect_cannot_open)
                }
            } finally {
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

    private fun extractFromSharedText(intent: Intent): String? {
        if (intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val matcher = Patterns.WEB_URL.matcher(text)
        return if (matcher.find()) text.substring(matcher.start(), matcher.end()) else null
    }

    private suspend fun route(url: String) {
        val app = applicationContext as LibRedirectApplication
        val manifest = withContext(Dispatchers.IO) { app.manifestRepository.activeManifest() }
        val settings = SettingsRepository(applicationContext).settings.first()

        val result =
            if (!settings.routingEnabled) {
                RoutingResult.Passthrough(url)
            } else if (manifest != null) {
                UrlRouter(manifest).resolve(url, settings.routingContext())
            } else {
                RoutingResult.Passthrough(url)
            }

        val destination = destinationFor(result) ?: return showMessage(R.string.redirect_cannot_open)

        val launcher = BrowserLauncher(packageManager, packageName)
        val launched = launcher.launch(this, destination, preferredPackage = settings.selectedBrowserPackage)
        if (!launched) {
            showMessage(R.string.redirect_no_browser)
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

    private fun showMessage(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "RedirectActivity"
    }
}
