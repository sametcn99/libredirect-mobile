package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route

/**
 * Builds the exact hostname -> route index once per [Manifest] so common
 * matching is O(1). Upstream services that contain wildcard subdomains use
 * the bounded hostname patterns as a fallback scan; exact hosts always win.
 * Routing must never blow up on a caller-provided manifest, so malformed
 * patterns are ignored after semantic validation has had its chance to reject
 * them.
 */
class RouteMatcher(manifest: Manifest) {
    private val index: Map<String, Route> =
        buildMap {
            for (route in manifest.routes) {
                for (host in route.hosts) {
                    put(host, route)
                }
            }
        }

    private val patternIndex: List<Pair<Regex, Route>> =
        manifest.routes.flatMap { route ->
            route.hostPatterns.mapNotNull { pattern ->
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE) to route }.getOrNull()
            }
        }

    fun match(host: String): Route? =
        index[host.lowercase()]
            ?: patternIndex.firstOrNull { (pattern, _) -> pattern.matches(host) }?.second
}
