package dev.libredirect.mobile.redirect

import dev.libredirect.mobile.browser.InAppRedirectInfo
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.routing.RoutingOutcome
import dev.libredirect.mobile.core.routing.RoutingTrace
import dev.libredirect.mobile.core.routing.TraceReason
import dev.libredirect.mobile.core.routing.UrlRouter
import dev.libredirect.mobile.core.url.QueryString
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.OriginalFallback
import dev.libredirect.mobile.ui.browser.BrowserDataRepository

data class LinkInspection(
    val original: String,
    val cleaned: String,
    val trace: RoutingTrace,
    val targetPackage: String?,
    val fallback: OriginalFallback?,
    val removedParameters: List<String>,
) {
    val destination: String?
        get() =
            trace.resultUrl.takeUnless {
                fallback in
                    setOf(
                        OriginalFallback.ASK, OriginalFallback.BLOCK,
                    )
            }

    fun redirectInfo() =
        trace.routeId?.let { routeId ->
            trace.frontendId?.takeIf { trace.outcome == RoutingOutcome.REDIRECT }?.let { frontendId ->
                InAppRedirectInfo(original, routeId, frontendId, trace.instance)
            }
        }
}

suspend fun inspectLink(
    input: String,
    settings: AppSettings,
    manifest: Manifest?,
    browserData: BrowserDataRepository,
): LinkInspection {
    val pipeline = LinkPipeline(settings)
    val prepared = pipeline.prepare(input, allowNetwork = false)
    val cleaned = pipeline.clean(prepared)
    val trace =
        if (manifest != null && settings.routingEnabled && !settings.isRoutingPaused()) {
            val context = pipeline.routingContext(pipeline.healthSnapshot(browserData), System.currentTimeMillis())
            UrlRouter(manifest).explain(prepared.url, context)
        } else {
            val reason =
                when {
                    settings.isRoutingPaused() -> TraceReason.PAUSED
                    !settings.routingEnabled -> TraceReason.ROUTING_DISABLED
                    else -> TraceReason.NO_ROUTE
                }
            RoutingTrace(input, RoutingOutcome.PASSTHROUGH, cleaned, reason = reason)
        }
    return LinkInspection(
        original = input,
        cleaned = cleaned,
        trace =
            trace.copy(
                unwrappedLayers = prepared.unwrappedLayers,
                strippedParams = trace.strippedParams + prepared.strippedQueryParams,
            ),
        targetPackage =
            settings.linkPolicies.targetPackage(
                UrlParser.parse(prepared.url)?.host,
                trace.routeId,
                settings.selectedBrowserPackage,
            ),
        fallback = settings.linkPolicies.fallback(trace),
        removedParameters = QueryString.names(UrlParser.parse(prepared.urlBeforeQueryCleanup)?.rawQuery)
            .distinct().minus(QueryString.names(UrlParser.parse(cleaned)?.rawQuery).toSet()),
    )
}
