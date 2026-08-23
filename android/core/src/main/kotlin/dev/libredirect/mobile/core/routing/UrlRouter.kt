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
    ): RoutingResult {
        if (input.length > UrlValidation.MAX_INPUT_LENGTH) {
            return RoutingResult.Failure(input, RoutingFailure.MALFORMED_URL)
        }
        val scheme =
            UrlParser.parseScheme(input)
                ?: return RoutingResult.Failure(input, RoutingFailure.MALFORMED_URL)

        if (scheme !in UrlValidation.ALLOWED_INPUT_SCHEMES) {
            return RoutingResult.Failure(input, RoutingFailure.UNSUPPORTED_SCHEME)
        }

        val url =
            UrlParser.parse(input)
                ?: return RoutingResult.Failure(input, RoutingFailure.MALFORMED_URL)

        if (ExceptionMatcher.matches(url, context.exceptions)) {
            return RoutingResult.Passthrough(input)
        }

        val route = routeMatcher.match(url.host) ?: return RoutingResult.Passthrough(input)
        if (route.id in context.disabledRoutes) return RoutingResult.Passthrough(input)

        val selectedFrontendId = context.selectedFrontends[route.id]
        val frontend =
            route.frontends.find { it.id == selectedFrontendId } ?: route.frontends.first()

        return applyStrategy(input, url, route.id, frontend, context)
    }

    private fun applyStrategy(
        input: String,
        url: ParsedUrl,
        routeId: String,
        frontend: Frontend,
        context: RoutingContext,
    ): RoutingResult {
        val redirected =
            when (val strategy = frontend.strategy) {
                is Strategy.Passthrough -> return RoutingResult.Passthrough(input)

                is Strategy.CustomScheme -> "${strategy.scheme}://$input"

                is Strategy.ReplaceOrigin -> {
                    val instance = pickInstance(routeId, frontend, context) ?: return noInstance(input)
                    replaceOrigin(url, instance)
                }

                is Strategy.Template -> {
                    val instance = pickInstance(routeId, frontend, context) ?: return noInstance(input)
                    TemplateRenderer.render(strategy.output, url, instance)
                }
            }

        return if (redirected == input) {
            RoutingResult.Passthrough(input)
        } else {
            RoutingResult.Redirect(input, redirected, routeId, frontend.id)
        }
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
