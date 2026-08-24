package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.url.ParsedUrl
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
    ): RoutingResult =
        if (input.length > UrlValidation.MAX_INPUT_LENGTH) {
            RoutingResult.Failure(input, RoutingFailure.MALFORMED_URL)
        } else {
            val scheme = UrlParser.parseScheme(input)
            when {
                scheme == null -> RoutingResult.Failure(input, RoutingFailure.MALFORMED_URL)
                scheme !in UrlValidation.ALLOWED_INPUT_SCHEMES ->
                    RoutingResult.Failure(input, RoutingFailure.UNSUPPORTED_SCHEME)
                else -> resolveParsed(input, context)
            }
        }

    private fun resolveParsed(
        input: String,
        context: RoutingContext,
    ): RoutingResult {
        val url = UrlParser.parse(input) ?: return RoutingResult.Failure(input, RoutingFailure.MALFORMED_URL)
        val route = routeMatcher.match(url.host)
        val selectedFrontendId = route?.let { context.selectedFrontends[it.id] }
        val frontend = route?.frontends?.find { it.id == selectedFrontendId } ?: route?.frontends?.firstOrNull()
        return when {
            ExceptionMatcher.matches(url, context.exceptions) -> RoutingResult.Passthrough(input)
            route == null || route.id in context.disabledRoutes -> RoutingResult.Passthrough(input)
            frontend == null -> RoutingResult.Passthrough(input)
            else -> applyStrategy(input, url, route.id, frontend, context)
        }
    }

    private fun applyStrategy(
        input: String,
        url: ParsedUrl,
        routeId: String,
        frontend: Frontend,
        context: RoutingContext,
    ): RoutingResult =
        when (val strategy = frontend.strategy) {
            is Strategy.Passthrough -> RoutingResult.Passthrough(input)
            is Strategy.CustomScheme -> redirectResult(input, "${strategy.scheme}://$input", routeId, frontend)
            is Strategy.ReplaceOrigin ->
                pickInstance(routeId, frontend, context)?.let { instance ->
                    redirectResult(input, replaceOrigin(url, instance), routeId, frontend)
                } ?: noInstance(input)
            is Strategy.Template ->
                pickInstance(routeId, frontend, context)?.let { instance ->
                    redirectResult(input, TemplateRenderer.render(strategy.output, url, instance), routeId, frontend)
                } ?: noInstance(input)
        }

    private fun redirectResult(
        input: String,
        redirected: String,
        routeId: String,
        frontend: Frontend,
    ): RoutingResult =
        if (redirected == input) {
            RoutingResult.Passthrough(input)
        } else {
            RoutingResult.Redirect(input, redirected, routeId, frontend.id)
        }

    private fun pickInstance(
        routeId: String,
        frontend: Frontend,
        context: RoutingContext,
    ): String? = instancePicker.pick(frontend, context.instanceSelectionFor(routeId, frontend.id))

    private fun noInstance(input: String) = RoutingResult.Failure(input, RoutingFailure.NO_INSTANCE_AVAILABLE)

    private fun replaceOrigin(
        url: ParsedUrl,
        instanceOrigin: String,
    ): String {
        val query = url.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = url.rawFragment?.let { "#$it" }.orEmpty()
        return "$instanceOrigin${url.rawPath}$query$fragment"
    }
}
