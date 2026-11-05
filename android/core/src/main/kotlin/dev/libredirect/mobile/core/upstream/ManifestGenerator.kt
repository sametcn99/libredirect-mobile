package dev.libredirect.mobile.core.upstream

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import java.time.Instant

/**
 * Generates a schemaVersion-1 manifest from live LibRedirect upstream data —
 * the in-app port of the former `tools/src/generate-routes.ts`. Per the
 * project's converter principle: never guess. A target/frontend/service that
 * can't be converted with full confidence is dropped and reported in
 * [Generation.notes], not approximated.
 *
 * @param instancesByFrontend kebab-cased frontend id -> HTTPS clearnet
 * instance origins (the output of the runtime data.json parser).
 */
object ManifestGenerator {
    /** Mirrors [dev.libredirect.mobile.core.manifest.ManifestValidator]'s route/frontend id grammar. */
    private val manifestIdPattern = Regex("^[a-z][a-z0-9-]{0,63}$")

    data class Generation(
        val manifest: Manifest,
        /** Unsupported targets / skipped frontends, for logging. Not user-facing. */
        val notes: List<String>,
    )

    fun generate(
        config: LibRedirectConfig,
        instancesByFrontend: Map<String, List<String>>,
        previousRevision: Int,
        generatedAt: Instant = Instant.now(),
    ): Generation {
        val notes = mutableListOf<String>()
        val routes = mutableListOf<Route>()
        for ((serviceKey, service) in config.services) {
            routes += convertService(serviceKey, service, instancesByFrontend, notes)
        }
        val manifest =
            Manifest(
                schemaVersion = 1,
                revision = previousRevision + 1,
                generatedAt = generatedAt.toString(),
                routes = routes,
            )
        return Generation(manifest, notes)
    }

    private data class ConvertibleFrontend(
        val id: String,
        val name: String,
        val excludedTargets: Set<Int>,
        val instances: List<String>,
    )

    private data class MatcherGroup(
        val frontendIds: MutableList<String> = mutableListOf(),
        val hosts: MutableSet<String> = LinkedHashSet(),
        val hostPatterns: MutableSet<String> = LinkedHashSet(),
    )

    private const val FALLBACK_FRONTEND_ID = "original"
    private val fallbackFrontend =
        Frontend(
            id = FALLBACK_FRONTEND_ID,
            name = "Original site",
            strategy = Strategy.Passthrough,
        )

    private fun convertService(
        serviceKey: String,
        service: LibRedirectService,
        instancesByFrontend: Map<String, List<String>>,
        notes: MutableList<String>,
    ): List<Route> {
        val matchers =
            if (service.targets.isEmpty()) {
                emptyMap()
            } else {
                matcherToTargetIndices(serviceKey, service, notes)
            }
        val serviceId = toKebabCase(serviceKey)
        val skipNote =
            when {
                service.targets.isEmpty() -> "skipped (no targets)"
                matchers.isEmpty() -> "skipped (no target produced a convertible host)"
                // e.g. a service key that kebab-cases into something starting
                // with a digit: the manifest id grammar cannot express it —
                // drop the whole service rather than guess a different id.
                !manifestIdPattern.matches(serviceId) ->
                    "skipped (service id '$serviceId' does not fit the manifest id grammar)"
                else -> null
            }
        if (skipNote != null) {
            notes += "$serviceKey: $skipNote"
            return emptyList()
        }

        val frontends = convertibleFrontends(serviceKey, service, instancesByFrontend, notes)
        val groups = groupMatchers(matchers, frontends)
        return routesFor(service, serviceId, frontends, groups)
    }

    /**
     * Maps each matcher (a finite host or an anchored hostname pattern) to
     * every target index that produced it. A host reachable via more than one
     * target pattern (common: a service's primary pattern and a narrower one
     * for a different path shape both match the same hostname) must be
     * deduplicated *before* grouping, with its final frontend applicability
     * computed as the intersection across all of its target indices —
     * otherwise the same host could end up assigned to two different
     * groups/routes depending on which entry the grouping loop happened to
     * see, which is exactly the kind of ambiguity the "duplicate host" check
     * exists to catch.
     */
    private fun matcherToTargetIndices(
        serviceKey: String,
        service: LibRedirectService,
        notes: MutableList<String>,
    ): Map<String, Set<Int>> {
        val matchers = LinkedHashMap<String, MutableSet<Int>>()
        service.targets.forEachIndexed { targetIndex, target ->
            val hosts = TargetHostExtractor.extractHosts(target)
            if (hosts != null) {
                for (host in hosts) {
                    matchers.getOrPut("host:$host") { mutableSetOf() } += targetIndex
                }
            } else {
                val hostPattern = TargetHostExtractor.extractHostPattern(target)
                if (hostPattern != null) {
                    matchers.getOrPut("pattern:$hostPattern") { mutableSetOf() } += targetIndex
                } else {
                    notes += "$serviceKey: target[$targetIndex] unsupported: $target"
                }
            }
        }
        return matchers
    }

    private fun convertibleFrontends(
        serviceKey: String,
        service: LibRedirectService,
        instancesByFrontend: Map<String, List<String>>,
        notes: MutableList<String>,
    ): List<ConvertibleFrontend> {
        val frontends = mutableListOf<ConvertibleFrontend>()
        for ((frontendKey, frontend) in service.frontends) {
            val instances =
                if (frontend.instanceList == true) {
                    // Lookup by kebab-cased key: the runtime data.json parser normalizes
                    // upstream's camelCase frontend keys to the manifest's frontend ids.
                    instancesByFrontend[toKebabCase(frontendKey)].orEmpty()
                } else {
                    emptyList()
                }
            val frontendId = toKebabCase(frontendKey)
            val skipNote =
                when {
                    frontend.instanceList != true -> "skipped (not a hosted instance frontend)"
                    !manifestIdPattern.matches(frontendId) ->
                        "skipped (frontend id '$frontendId' does not fit the manifest id grammar)"
                    instances.isEmpty() -> "skipped (no usable HTTPS clearnet instances)"
                    else -> null
                }
            if (skipNote == null) {
                frontends +=
                    ConvertibleFrontend(
                        id = frontendId,
                        name = frontend.name,
                        excludedTargets = frontend.excludeTargets.toSet(),
                        instances = instances,
                    )
            } else {
                notes += "$serviceKey.$frontendKey: $skipNote"
            }
        }
        return frontends
    }

    // Group hosts by which frontends actually apply to them. A host is often
    // matched by more than one target pattern for the same reason a broader
    // pattern and a narrower, redundant one both exist (e.g. target[0]
    // already matches youtube.com/watch URLs generally, and target[3]
    // separately re-matches the same URLs more specifically) — a frontend
    // applies if it's valid for AT LEAST ONE of those patterns, not only if
    // it's valid for every one of them. The alternative (require all) was
    // tried first and rejected: it excluded Piped, one of the most-used
    // YouTube frontends, from the main youtube.com route purely because a
    // redundant secondary pattern for the same hosts happened to be in
    // Piped's excludeTargets, which produces a worse, more surprising result
    // than the ambiguity this union interpretation accepts instead.
    private fun groupMatchers(
        matcherToTargetIndices: Map<String, Set<Int>>,
        frontends: List<ConvertibleFrontend>,
    ): Map<String, MatcherGroup> {
        val groups = LinkedHashMap<String, MatcherGroup>()
        for ((matcher, targetIndices) in matcherToTargetIndices) {
            val applicable =
                frontends
                    .filter { frontend -> targetIndices.any { index -> index !in frontend.excludedTargets } }
                    .map { it.id }
                    .sorted()
            val group =
                groups.getOrPut(applicable.joinToString(",")) {
                    MatcherGroup(frontendIds = applicable.toMutableList())
                }
            if (matcher.startsWith("host:")) {
                group.hosts += matcher.removePrefix("host:")
            } else {
                group.hostPatterns += matcher.removePrefix("pattern:")
            }
        }
        return groups
    }

    private fun routesFor(
        service: LibRedirectService,
        serviceId: String,
        frontends: List<ConvertibleFrontend>,
        groups: Map<String, MatcherGroup>,
    ): List<Route> {
        val frontendById = frontends.associateBy { it.id }
        val routes = mutableListOf<Route>()
        var groupIndex = 0
        for (group in groups.values) {
            val frontendIds = group.frontendIds.ifEmpty { listOf(FALLBACK_FRONTEND_ID) }
            val isPrimary = groupIndex == 0
            routes +=
                Route(
                    id = if (isPrimary) serviceId else "$serviceId-$groupIndex",
                    name = if (isPrimary) service.name else "${service.name} (additional hosts)",
                    hosts = group.hosts.toList(),
                    hostPatterns = group.hostPatterns.toList(),
                    frontends =
                        frontendIds.map { frontendId ->
                            if (frontendId == FALLBACK_FRONTEND_ID) {
                                fallbackFrontend
                            } else {
                                val frontend =
                                    checkNotNull(frontendById[frontendId]) {
                                        "internal error: convertible frontend '$frontendId' vanished during grouping"
                                    }
                                Frontend(
                                    id = frontend.id,
                                    name = frontend.name,
                                    strategy = Strategy.ReplaceOrigin,
                                    instances = frontend.instances,
                                )
                            }
                        },
                )
            groupIndex++
        }
        return routes
    }

    internal fun toKebabCase(key: String): String =
        key
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trimStart('-')
            .trimEnd('-')
}
