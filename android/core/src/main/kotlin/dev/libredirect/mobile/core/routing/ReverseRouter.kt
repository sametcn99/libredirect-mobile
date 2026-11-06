package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.url.ParsedUrl
import dev.libredirect.mobile.core.url.UrlParser

/**
 * Reverse routing (2.7): given a frontend URL, compute the original service
 * URL it most plausibly maps to. Strictly deterministic and limited to
 * shapes that reverse without guessing — "never guess" applies in both
 * directions, so ambiguous cases return null:
 *
 * - `replace-origin` frontends: the URL's host must be one of the
 *   frontend's instances; the original is the route's first host with the
 *   same path/query/fragment.
 * - `template` frontends: only the exact shapes `{instance}{path}` and
 *   `{instance}{path}{fragment}` reverse reliably — the rendered host is the
 *   instance host and everything after it is the original path verbatim.
 *   Any other template mixes literal text and placeholders in ways that
 *   cannot be split back with certainty.
 */
object ReverseRouter {
    private val PURE_PREFIX_TEMPLATE = Regex("^\\{instance\\}\\{path\\}(\\{fragment\\})?$")

    /**
     * The original service URL for [input], or null when no route/frontend
     * reverses this URL with certainty. Frontend URLs are looked up by
     * *instance* host (the opposite direction of [RouteMatcher]); when more
     * than one route/frontend claims the same instance host the ownership is
     * ambiguous and null is returned — never guess.
     */
    @Suppress("ReturnCount")
    fun reverse(
        input: String,
        manifest: Manifest,
    ): String? {
        val url = UrlParser.parse(input) ?: return null
        val host = url.host
        val claims =
            manifest.routes
                .flatMap { route -> route.frontends.map { route to it } }
                .filter { (_, frontend) -> frontend.hasInstanceHost(host) }
        if (claims.size != 1) return null
        val (route, frontend) = claims.first()
        return when (frontend.strategy) {
            is Strategy.ReplaceOrigin -> reverseReplaceOrigin(route.hosts, url)
            is Strategy.Template -> reverseTemplate(route.hosts, url, frontend)
            else -> null
        }
    }

    @Suppress("ReturnCount")
    private fun reverseReplaceOrigin(
        serviceHosts: List<String>,
        url: ParsedUrl,
    ): String? {
        val serviceHost = serviceHosts.firstOrNull() ?: return null
        return joinServiceUrl(serviceHost, url)
    }

    private fun reverseTemplate(
        serviceHosts: List<String>,
        url: ParsedUrl,
        frontend: Frontend,
    ): String? =
        (frontend.strategy as? Strategy.Template)?.output
            ?.takeIf { template -> PURE_PREFIX_TEMPLATE.matches(template) }
            ?.let { template ->
                serviceHosts.firstOrNull()?.let { serviceHost ->
                    val fragment = url.rawFragment?.let { value -> "#$value" }.orEmpty()
                    "https://$serviceHost${url.rawPath}$fragment"
                }
            }

    private fun Frontend.hasInstanceHost(host: String): Boolean =
        instances.any { instance -> instanceHostOf(instance) == host }

    private fun instanceHostOf(instanceOrigin: String): String =
        instanceOrigin.removePrefix(
            "https://",
        ).substringBefore(':')

    private fun joinServiceUrl(
        serviceHost: String,
        url: ParsedUrl,
    ): String {
        val query = url.rawQuery?.let { value -> "?$value" }.orEmpty()
        val fragment = url.rawFragment?.let { value -> "#$value" }.orEmpty()
        return "https://$serviceHost${url.rawPath}$query$fragment"
    }
}
