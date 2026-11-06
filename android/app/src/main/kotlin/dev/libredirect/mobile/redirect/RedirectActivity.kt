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
import dev.libredirect.mobile.core.url.TrackingParams
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.diagnostics.DiagnosticsLog
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
import dev.libredirect.mobile.ui.browser.BrowserDataRepository
import dev.libredirect.mobile.ui.browser.PrivacyStats
import dev.libredirect.mobile.ui.browser.SearchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * No UI (see the NoDisplay theme in AndroidManifest.xml): this activity
 * exists only to compute a destination and hand off to [BrowserLauncher]
 * before finishing, per the project plan's performance target
 * (route resolution should not block on drawing anything).
 *
 * Pipeline order (2.x/3.x): extract the URL → unwrap wrappers (3.1) →
 * optionally resolve shorteners (3.3) → route with health data (2.1) →
 * ask dialog when the service demands it (2.4) → launch; privacy counters
 * land in the local stats store (3.4).
 */
class RedirectActivity : ComponentActivity() {
    @Suppress("TooGenericExceptionCaught")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as LibRedirectApplication
        // The referrer must be read now: by the time the routing coroutine
        // runs this activity may already be destroyed (2.5).
        val referrer = getReferrer()?.toString()
        // Route on the application scope, not the activity's: this screen is
        // invisible and finishes (or is torn down) as soon as the user moves
        // on — a lifecycle-scoped coroutine would be cancelled mid-route and
        // silently drop the link.
        app.applicationScope.launch {
            try {
                val incomingUrl = extractUrl(intent)
                if (incomingUrl != null) route(app, incomingUrl, referrer)
            } catch (_: CancellationException) {
                // Scope cancellation means the process is going away anyway.
            } catch (error: Exception) {
                Log.e(TAG, "Could not route incoming URL", error)
                DiagnosticsLog.record(TAG, "Routing failed: ${error.message}")
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
            // C2: "LibRedirect ile aç" in other apps' text-selection menu. A
            // selection that is a URL gets routed; anything else becomes a
            // search in the user's search engine (see [route]).
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
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

    @Suppress("LongMethod")
    private suspend fun route(
        app: LibRedirectApplication,
        url: String,
        referrer: String?,
    ) {
        val manifest = withContext(Dispatchers.IO) { app.manifestRepository.activeManifest() }
        val settings = SettingsRepository(app).current()
        val dataRepository = BrowserDataRepository(app)
        val sourceApp = LinkPipeline.sourcePackageFromReferrer(referrer)
        val pipeline = LinkPipeline(settings, sourceApp)

        // Process-text input that is not a URL becomes a search in the user's
        // engine instead of being rejected as an unopenable string.
        val effectiveUrl =
            ProcessTextResolver.resolve(
                text = url,
                engine =
                    SearchEngine.resolve(
                        selectedId = settings.searchEngine,
                        customName = settings.customSearchEngineName,
                        customUrl = settings.customSearchEngineUrl,
                    ),
            )

        val prepared = pipeline.prepare(effectiveUrl)
        val health = pipeline.healthSnapshot(dataRepository)
        val context = pipeline.routingContext(health, nowMillis = System.currentTimeMillis())

        // Paused routing (2.6) behaves exactly like the master switch off:
        // links pass through with tracking cleanup only.
        val routingActive = settings.routingEnabled && !settings.isRoutingPaused()
        val result =
            if (!routingActive || manifest == null) {
                RoutingResult.Passthrough(prepared.url)
            } else {
                dev.libredirect.mobile.core.routing.UrlRouter(manifest).resolve(prepared.url, context)
            }

        val destination = destinationFor(result) ?: return showMessage(app, R.string.redirect_cannot_open)

        // 2.4: services in "ask every time" mode open the lightweight ask
        // dialog instead of redirecting; the dialog finishes the launch.
        val redirect = result as? RoutingResult.Redirect
        if (redirect != null && settings.isRouteAskMode(redirect.routeId)) {
            AskRouteActivity.start(app, redirect, prepared.url)
            return
        }

        if (redirect != null) {
            recordOutcome(dataRepository, settings, prepared, redirected = true)
            dataRepository.recordRedirect(redirect.routeId)
        } else if (result is RoutingResult.Passthrough && prepared.url != effectiveUrl) {
            recordOutcome(dataRepository, settings, prepared, redirected = false)
        }

        val redirectInfo =
            redirect?.let {
                InAppRedirectInfo(
                    originalUrl = prepared.url,
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
     * Local privacy counters (3.4): one redirect/passthrough event plus the
     * tracking parameters the router cleaned off the routed URL and the
     * wrappers/short-links the pipeline unwrapped on the way.
     */
    private suspend fun recordOutcome(
        dataRepository: BrowserDataRepository,
        settings: AppSettings,
        prepared: LinkPipeline.Prepared,
        redirected: Boolean,
    ) {
        val parsed = UrlParser.parse(prepared.url)
        val scope = settings.customTrackingRules()
        val stripped =
            parsed?.let {
                TrackingParams.removedCount(
                    it.rawQuery,
                    it.host,
                    dev.libredirect.mobile.core.url.CustomTrackingScope(scope.global, scope.byHost),
                )
            } ?: 0
        dataRepository.addPrivacyStats(
            PrivacyStats(
                totalRedirects = if (redirected) 1 else 0,
                strippedTrackingParams = stripped.toLong(),
                unwrappedWrappers = prepared.unwrappedLayers.toLong(),
                resolvedShortLinks = if (prepared.shortLinkResolved) 1 else 0,
            ),
        )
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
