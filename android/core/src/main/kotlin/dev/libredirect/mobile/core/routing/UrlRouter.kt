package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.url.CustomTrackingScope
import dev.libredirect.mobile.core.url.ParsedUrl
import dev.libredirect.mobile.core.url.TrackingParams
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.core.url.UrlValidation
import kotlin.random.Random

class UrlRouter(
    manifest: Manifest,
    random: Random = Random.Default,
) : Router {
    private val routeMatcher = RouteMatcher(manifest)
    private val instancePicker = InstancePicker(random)

    override fun resolve(
        input: String,
        context: RoutingContext,
    ): RoutingResult = decide(input, context).result

    /**
     * Same decision as [resolve], with a structured trace for the "why?" card
     * (2.8). The outcome is identical to [resolve] for the same inputs.
     */
    fun explain(
        input: String,
        context: RoutingContext,
    ): RoutingTrace = decide(input, context).trace

    private fun decide(
        input: String,
        context: RoutingContext,
    ): Decision {
        if (input.length > UrlValidation.MAX_INPUT_LENGTH) {
            return failure(input, RoutingFailure.MALFORMED_URL, TraceReason.NOT_ELIGIBLE)
        }
        val scheme = UrlParser.parseScheme(input)
        return when {
            scheme == null -> failure(input, RoutingFailure.MALFORMED_URL, TraceReason.NOT_ELIGIBLE)
            scheme !in UrlValidation.ALLOWED_INPUT_SCHEMES ->
                failure(input, RoutingFailure.UNSUPPORTED_SCHEME, TraceReason.NOT_ELIGIBLE)
            else -> decideEligible(input, context)
        }
    }

    private fun decideEligible(
        input: String,
        context: RoutingContext,
    ): Decision {
        val url =
            UrlParser.parse(input)
                ?: return failure(input, RoutingFailure.MALFORMED_URL, TraceReason.NOT_ELIGIBLE)
        val route = routeMatcher.match(url.host)
        val selectedFrontendId = route?.let { context.selectedFrontends[it.id] }
        val frontend = route?.frontends?.find { it.id == selectedFrontendId } ?: route?.frontends?.firstOrNull()
        val scope = context.customTrackingScope()
        return when {
            // Explicit user exceptions keep the URL byte-for-byte intact.
            ExceptionMatcher.matches(url, context.exceptions, context.sourceApp) -> {
                val detail = matchedExceptionLabel(context.exceptions, url, context.sourceApp)
                Decision(
                    RoutingResult.Passthrough(input),
                    passthroughTrace(input, TraceReason.EXCEPTION_MATCHED, detail = detail),
                )
            }

            route == null ->
                Decision(
                    RoutingResult.Passthrough(TrackingParams.stripFrom(input, url, scope)),
                    passthroughTrace(TrackingParams.stripFrom(input, url, scope), TraceReason.NO_ROUTE),
                )

            route.id in context.disabledRoutes ->
                Decision(
                    RoutingResult.Passthrough(TrackingParams.stripFrom(input, url, scope)),
                    passthroughTrace(TrackingParams.stripFrom(input, url, scope), TraceReason.ROUTE_DISABLED, route.id),
                )

            frontend == null ->
                Decision(
                    RoutingResult.Passthrough(TrackingParams.stripFrom(input, url, scope)),
                    passthroughTrace(TrackingParams.stripFrom(input, url, scope), TraceReason.NO_ROUTE, route.id),
                )

            route.id in context.askModeRoutes ->
                Decision(
                    RoutingResult.Passthrough(TrackingParams.stripFrom(input, url, scope)),
                    passthroughTrace(TrackingParams.stripFrom(input, url, scope), TraceReason.ASK_MODE, route.id),
                )

            else -> applyStrategy(input, url, route.id, frontend, context)
        }
    }

    private fun applyStrategy(
        input: String,
        url: ParsedUrl,
        routeId: String,
        frontend: Frontend,
        context: RoutingContext,
    ): Decision {
        val scope = context.customTrackingScope()
        val cleanUrl = TrackingParams.stripped(url, scope)
        val target =
            TargetInfo(
                routeId = routeId,
                frontend = frontend,
                context = context,
                strippedCount = TrackingParams.removedCount(url.rawQuery, url.host, scope),
            )
        return when (val strategy = frontend.strategy) {
            is Strategy.Passthrough -> {
                val kept = TrackingParams.stripFrom(input, url, scope)
                Decision(
                    RoutingResult.Passthrough(kept),
                    passthroughTrace(
                        kept,
                        TraceReason.STRATEGY_PASSTHROUGH,
                        routeId,
                        frontend.id,
                        target.strippedCount,
                    ),
                )
            }
            is Strategy.CustomScheme -> {
                val redirected = "${strategy.scheme}://${TrackingParams.stripFrom(input, url, scope)}"
                redirect(input, redirected, null, target)
            }
            is Strategy.ReplaceOrigin ->
                pickInstance(target)?.let { instance ->
                    redirect(input, replaceOrigin(cleanUrl, instance), instance, target)
                } ?: noInstance(input, target)
            is Strategy.Template ->
                pickInstance(target)?.let { instance ->
                    redirect(input, TemplateRenderer.render(strategy.output, cleanUrl, instance), instance, target)
                } ?: noInstance(input, target)
        }
    }

    private fun redirect(
        input: String,
        redirected: String,
        instance: String?,
        target: TargetInfo,
    ): Decision =
        if (redirected == input) {
            Decision(
                RoutingResult.Passthrough(input),
                passthroughTrace(
                    input,
                    TraceReason.TARGET_UNCHANGED,
                    target.routeId,
                    target.frontend.id,
                    target.strippedCount,
                    instance,
                ),
            )
        } else {
            Decision(
                RoutingResult.Redirect(input, redirected, target.routeId, target.frontend.id, instance),
                RoutingTrace(
                    input = input,
                    outcome = RoutingOutcome.REDIRECT,
                    resultUrl = redirected,
                    routeId = target.routeId,
                    frontendId = target.frontend.id,
                    selectionMode = selectionModeLabel(target, instance),
                    instance = instance,
                    strippedParams = target.strippedCount,
                ),
            )
        }

    private fun noInstance(
        input: String,
        target: TargetInfo,
    ): Decision =
        failure(
            input,
            RoutingFailure.NO_INSTANCE_AVAILABLE,
            TraceReason.NO_INSTANCE,
            target.routeId,
            target.frontend.id,
        )

    // Optional fields of RoutingTrace at call sites read clearer as named
    // arguments than bundled into an artificial parameter object.
    @Suppress("LongParameterList")
    private fun passthroughTrace(
        url: String,
        reason: TraceReason,
        routeId: String? = null,
        frontendId: String? = null,
        strippedCount: Int = 0,
        instance: String? = null,
        detail: String? = null,
    ): RoutingTrace =
        // The trace for a passed-through URL is always the URL itself.
        RoutingTrace(
            input = url,
            outcome = RoutingOutcome.PASSTHROUGH,
            resultUrl = url,
            routeId = routeId,
            frontendId = frontendId,
            instance = instance,
            strippedParams = strippedCount,
            reason = reason,
            detail = detail,
        )

    private fun failure(
        input: String,
        failure: RoutingFailure,
        reason: TraceReason,
        routeId: String? = null,
        frontendId: String? = null,
    ): Decision =
        Decision(
            RoutingResult.Failure(input, failure),
            RoutingTrace(
                input = input,
                outcome = RoutingOutcome.FAILURE,
                routeId = routeId,
                frontendId = frontendId,
                reason = reason,
            ),
        )

    // Guard chain: override lookup → selection; single expression keeps the
    // precedence readable.
    private fun pickInstance(target: TargetInfo): String? =
        target.context.instanceOverrides[target.overrideKey]
            ?.takeIf { it in target.frontend.instances }
            ?: instancePicker.pick(
                target.frontend,
                target.context.instanceSelectionFor(target.routeId, target.frontend.id),
                target.context.instanceHealth,
                target.context.nowMillis,
            )

    private fun selectionModeLabel(
        target: TargetInfo,
        instance: String?,
    ): String? {
        if (instance == null) return null
        val mode =
            when (target.context.instanceSelectionFor(target.routeId, target.frontend.id)) {
                is InstanceSelection.Automatic -> "automatic"
                is InstanceSelection.Fastest -> "fastest"
                is InstanceSelection.Pinned -> "pinned"
                is InstanceSelection.Custom -> "custom"
            }
        return if (target.context.instanceOverrides.containsKey(target.overrideKey)) "override" else mode
    }

    private fun replaceOrigin(
        url: ParsedUrl,
        instanceOrigin: String,
    ): String {
        val query = url.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = url.rawFragment?.let { "#$it" }.orEmpty()
        return "$instanceOrigin${url.rawPath}$query$fragment"
    }

    private fun matchedExceptionLabel(
        rules: List<ExceptionRule>,
        url: ParsedUrl,
        sourceApp: String?,
    ): String? =
        rules.firstOrNull { rule -> ExceptionMatcher.matches(url, listOf(rule), sourceApp) }?.let { rule ->
            when (rule) {
                is ExceptionRule.Domain -> rule.host
                is ExceptionRule.UrlPrefix -> rule.value
                is ExceptionRule.SourceApp -> rule.packageName
            }
        }

    private fun RoutingContext.customTrackingScope(): CustomTrackingScope =
        CustomTrackingScope(global = customTrackingRules.global, byHost = customTrackingRules.byHost)

    /** Bundles what a redirect decision needs, keeping helper signatures small. */
    private data class TargetInfo(
        val routeId: String,
        val frontend: Frontend,
        val context: RoutingContext,
        val strippedCount: Int,
    ) {
        val overrideKey: String get() = "$routeId/${frontend.id}"
    }

    private data class Decision(
        val result: RoutingResult,
        val trace: RoutingTrace,
    )
}
