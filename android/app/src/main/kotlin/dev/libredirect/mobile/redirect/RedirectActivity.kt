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
 * optionally resolve shorteners (3.3) → optionally strip non-functional
 * query parameters → route with health data (2.1) → ask dialog when the
 * service demands it (2.4) → launch; privacy counters land in the local
 * stats store (3.4).
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
            } catch (error: Throwable) {
                // Throwable, not Exception: an Error (R8/minification linkage
                // failures have happened before — see v1.3.7) must land here
                // with a diagnostic record, not in the crash-recovery handler
                // that restarts MainActivity and silently loses this log.
                Log.e(TAG, "Could not route incoming URL", error)
                DiagnosticsLog.record(TAG, "Routing failed: ${error.message}")
                showMessage(app, R.string.redirect_cannot_open)
            } finally {
                // Always finish on the main thread so the translucent proxy window closes cleanly.
                finishOnMain()
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
        // engine instead of being rejected as an unopenable string. The
        // engine-built URL must then keep its query (it IS the payload), so
        // query-param stripping is skipped for that case.
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

        val prepared = pipeline.prepare(effectiveUrl, allowQueryParamStrip = effectiveUrl == url)
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
            // Resolve display names here, off the main thread — the manifest
            // lookup may still hit disk on a cold start.
            val askRoute = manifest?.routes?.find { it.id == redirect.routeId }
            val displayNames =
                AskRouteActivity.DisplayNames(
                    serviceName = askRoute?.name ?: redirect.routeId,
                    frontendName =
                        askRoute?.frontends?.find { it.id == redirect.frontendId }?.name
                            ?: redirect.frontendId,
                )
            withContext(Dispatchers.Main.immediate) {
                val launchContext = activeLaunchContext(app)
                AskRouteActivity.start(
                    launchContext,
                    redirect,
                    displayNames = displayNames,
                    preferredBrowserPackage = settings.selectedBrowserPackage,
                    hasBrowserPreference = true,
                )
                finishIfAlive()
            }
            return
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
        // Hand off to the browser on the main thread using the alive Activity
        // context first (prevents Android 14+ BAL blocks), finish the
        // translucent proxy immediately, and persist stats in the background.
        val launched =
            withContext(Dispatchers.Main.immediate) {
                val launchContext = activeLaunchContext(app)
                val ok =
                    BrowserLauncher(launchContext).launch(
                        launchContext,
                        destination,
                        preferredPackage = settings.selectedBrowserPackage,
                        redirect = redirectInfo,
                    )
                finishIfAlive()
                ok
            }
        if (!launched) {
            showMessage(app, R.string.redirect_no_browser)
        } else if (redirect != null || (result is RoutingResult.Passthrough && prepared.url != effectiveUrl)) {
            scheduleStatsRecord(
                app,
                PendingRedirectStats(
                    dataRepository = dataRepository,
                    settings = settings,
                    prepared = prepared,
                    redirectRouteId = redirect?.routeId,
                ),
            )
        }
    }

    private fun activeLaunchContext(app: LibRedirectApplication): Context =
        if (!isFinishing && !isDestroyed) this@RedirectActivity else app

    private fun finishIfAlive() {
        if (!isFinishing && !isDestroyed) finish()
    }

    private suspend fun finishOnMain() {
        withContext(Dispatchers.Main.immediate) {
            finishIfAlive()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleStatsRecord(
        app: LibRedirectApplication,
        stats: PendingRedirectStats,
    ) {
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                recordOutcome(
                    stats.dataRepository,
                    stats.settings,
                    stats.prepared,
                    redirected = stats.redirectRouteId != null,
                )
                stats.redirectRouteId?.let { routeId ->
                    stats.dataRepository.recordRedirect(routeId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not persist redirect statistics", error)
            }
        }
    }

    private data class PendingRedirectStats(
        val dataRepository: BrowserDataRepository,
        val settings: AppSettings,
        val prepared: LinkPipeline.Prepared,
        val redirectRouteId: String?,
    )

    /**
     * Local privacy counters (3.4): one redirect/passthrough event plus the
     * tracking parameters the router cleaned off the routed URL, the
     * non-functional query parameters the pipeline stripped, and the
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
                strippedTrackingParams = (stripped + prepared.strippedQueryParams).toLong(),
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

    private suspend fun showMessage(
        context: Context,
        messageRes: Int,
    ) {
        // Toast creation throws without a prepared Looper; callers may run on Default/IO.
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context.applicationContext, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "RedirectActivity"
    }
}
