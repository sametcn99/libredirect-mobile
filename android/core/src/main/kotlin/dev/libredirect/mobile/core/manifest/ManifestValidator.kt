package dev.libredirect.mobile.core.manifest

import java.net.URI
import java.time.Instant

/**
 * Semantic validation shared by bundled, persisted, and remote manifests.
 * JSON decoding protects the type shape; this protects the routing invariants
 * that JSON Schema cannot express across records.
 */
object ManifestValidator {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private const val MAX_ROUTE_COUNT = 2_000
    private const val MAX_ROUTE_NAME_LENGTH = 80
    private const val MAX_HOST_COUNT = 32
    private const val MAX_HOST_MATCHER_COUNT = 64
    private const val MAX_HOST_PATTERN_COUNT = 16
    private const val MAX_FRONTEND_COUNT = 16
    private const val MAX_INSTANCE_COUNT = 64
    private const val MAX_HOST_PATTERN_LENGTH = 256
    private const val MAX_TEMPLATE_LENGTH = 512
    private const val CONTROL_CHARACTER_LIMIT = 0x20
    private const val MIN_IPV4_OCTET = 0
    private const val MAX_IPV4_OCTET = 255
    private const val UNSPECIFIED_OCTET = 0
    private const val LOOPBACK_OCTET = 127
    private const val PRIVATE_CLASS_A_OCTET = 10
    private const val LINK_LOCAL_FIRST_OCTET = 169
    private const val LINK_LOCAL_SECOND_OCTET = 254
    private const val PRIVATE_NETWORK_FIRST_OCTET = 172
    private const val PRIVATE_NETWORK_START = 16
    private const val PRIVATE_NETWORK_END = 31
    private const val PRIVATE_CLASS_C_FIRST_OCTET = 192
    private const val PRIVATE_CLASS_C_SECOND_OCTET = 168

    private val idPattern = Regex("^[a-z][a-z0-9-]{0,63}$")
    private val hostnamePattern =
        Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val instancePattern =
        Regex("^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{1,5})?$")
    private val schemePattern = Regex("^[a-z][a-z0-9+.-]{1,31}$")
    private val placeholderPattern = Regex("\\{(instance|path|fragment|query:[A-Za-z0-9_]+)\\}")
    private val boundedQuantifierPattern = Regex("\\{[0-9]{1,2}(,[0-9]{1,2})?\\}")
    private val ipv4Pattern = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

    fun validate(manifest: Manifest): List<String> =
        buildList {
            validateManifestMetadata(manifest, this)

            val routeIds = mutableSetOf<String>()
            val hosts = mutableSetOf<String>()
            if (manifest.routes.size > MAX_ROUTE_COUNT) add("too many routes")
            manifest.routes.forEach { route -> validateRoute(route, routeIds, hosts, this) }
        }

    private fun validateManifestMetadata(
        manifest: Manifest,
        errors: MutableList<String>,
    ) {
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            errors += "unsupported schemaVersion=${manifest.schemaVersion}"
        }
        if (manifest.revision < 1) errors += "revision must be positive"
        if (manifest.generatedAt.isBlank()) {
            errors += "generatedAt must not be blank"
        } else {
            try {
                Instant.parse(manifest.generatedAt)
            } catch (_: RuntimeException) {
                errors += "generatedAt is not an ISO-8601 timestamp"
            }
        }
    }

    private fun validateRoute(
        route: Route,
        routeIds: MutableSet<String>,
        hosts: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        validateRouteMetadata(route, routeIds, errors)
        validateRouteHosts(route, hosts, errors)
        validateRouteFrontends(route, errors)
    }

    private fun validateRouteMetadata(
        route: Route,
        routeIds: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        if (!idPattern.matches(route.id)) errors += "invalid route id '${route.id}'"
        if (!routeIds.add(route.id)) errors += "duplicate route id '${route.id}'"
        if (route.name.isBlank() || route.name.length > MAX_ROUTE_NAME_LENGTH) {
            errors += "route '${route.id}' has an invalid name"
        }
        if (route.hosts.size > MAX_HOST_COUNT || route.hosts.size + route.hostPatterns.size > MAX_HOST_MATCHER_COUNT) {
            errors += "route '${route.id}' has an invalid host count"
        }
        if (route.hosts.isEmpty() && route.hostPatterns.isEmpty()) {
            errors += "route '${route.id}' has no host matchers"
        }
        if (route.hostPatterns.size > MAX_HOST_PATTERN_COUNT) {
            errors += "route '${route.id}' has too many host patterns"
        }
        if (route.frontends.isEmpty() || route.frontends.size > MAX_FRONTEND_COUNT) {
            errors += "route '${route.id}' has an invalid frontend count"
        }
    }

    private fun validateRouteHosts(
        route: Route,
        hosts: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        route.hosts.forEach { host ->
            if (!hostnamePattern.matches(host) || host != host.lowercase()) {
                errors += "route '${route.id}' has invalid host '$host'"
            }
            if (!hosts.add(host)) errors += "duplicate host '$host'"
        }
        route.hostPatterns.forEach { pattern -> validateHostPattern(route.id, pattern, errors) }
    }

    private fun validateRouteFrontends(
        route: Route,
        errors: MutableList<String>,
    ) {
        val frontendIds = mutableSetOf<String>()
        route.frontends.forEach { frontend ->
            if (!idPattern.matches(frontend.id)) {
                errors += "route '${route.id}' has invalid frontend id '${frontend.id}'"
            }
            if (!frontendIds.add(frontend.id)) {
                errors += "route '${route.id}' has duplicate frontend id '${frontend.id}'"
            }
            validateFrontend(route.id, frontend, errors)
        }
    }

    private fun validateHostPattern(
        routeId: String,
        pattern: String,
        errors: MutableList<String>,
    ) {
        when {
            isInvalidHostPattern(pattern) -> errors += "route '$routeId' has an invalid host pattern"
            isUnsafeHostPattern(pattern) -> errors += "route '$routeId' has an unsafe host pattern"
            else -> validateHostPatternSyntax(routeId, pattern, errors)
        }
    }

    private fun isInvalidHostPattern(pattern: String): Boolean =
        pattern.length > MAX_HOST_PATTERN_LENGTH ||
            pattern.any { it.code < CONTROL_CHARACTER_LIMIT } ||
            !pattern.startsWith("^") ||
            !pattern.endsWith("$")

    private fun isUnsafeHostPattern(pattern: String): Boolean {
        val blockedTokens = listOf("/", "\\\\", "(?<", ".*", ".+", "\\1", "\\2", "\\3")
        val hasBlockedToken = blockedTokens.any(pattern::contains)
        val hasUnboundedQuantifier = boundedQuantifierPattern.replace(pattern, "").any { it == '{' || it == '}' }
        return hasBlockedToken || hasUnboundedQuantifier
    }

    private fun validateHostPatternSyntax(
        routeId: String,
        pattern: String,
        errors: MutableList<String>,
    ) {
        try {
            Regex(pattern)
        } catch (_: RuntimeException) {
            errors += "route '$routeId' has an invalid host pattern"
        }
    }

    fun requireValid(manifest: Manifest): Manifest {
        val errors = validate(manifest)
        require(errors.isEmpty()) { "Invalid manifest: ${errors.joinToString("; ")}" }
        return manifest
    }

    private fun validateFrontend(
        routeId: String,
        frontend: Frontend,
        errors: MutableList<String>,
    ) {
        if (frontend.name.isBlank() || frontend.name.length > MAX_ROUTE_NAME_LENGTH) {
            errors += "frontend '${frontend.id}' has an invalid name"
        }
        if (frontend.instances.size > MAX_INSTANCE_COUNT) errors += "frontend '${frontend.id}' has too many instances"
        validateStrategy(routeId, frontend, errors)

        val uniqueInstances = mutableSetOf<String>()
        frontend.instances.forEach { instance ->
            if (!instancePattern.matches(instance) || !isPublicOrigin(instance)) {
                errors += "frontend '${frontend.id}' has invalid or private instance '$instance'"
            }
            if (!uniqueInstances.add(instance)) errors += "frontend '${frontend.id}' has duplicate instance '$instance'"
        }
    }

    private fun validateStrategy(
        routeId: String,
        frontend: Frontend,
        errors: MutableList<String>,
    ) {
        when (val strategy = frontend.strategy) {
            Strategy.ReplaceOrigin -> {
                if (frontend.instances.isEmpty()) errors += "frontend '${frontend.id}' needs instances"
            }
            is Strategy.Template -> {
                if (frontend.instances.isEmpty()) errors += "frontend '${frontend.id}' needs instances"
                validateTemplate(routeId, frontend.id, strategy.output, errors)
            }
            is Strategy.CustomScheme -> {
                if (!schemePattern.matches(strategy.scheme)) {
                    errors += "frontend '${frontend.id}' has invalid custom scheme"
                }
                if (frontend.instances.isNotEmpty()) {
                    errors += "frontend '${frontend.id}' must not declare instances"
                }
            }
            Strategy.Passthrough -> {
                if (frontend.instances.isNotEmpty()) {
                    errors += "frontend '${frontend.id}' must not declare instances"
                }
            }
        }
    }

    private fun validateTemplate(
        routeId: String,
        frontendId: String,
        output: String,
        errors: MutableList<String>,
    ) {
        val hasInvalidLength = output.isBlank() || output.length > MAX_TEMPLATE_LENGTH
        val hasControlCharacter = output.any { it.code < CONTROL_CHARACTER_LIMIT }
        if (hasInvalidLength || hasControlCharacter) {
            errors += "route '$routeId' frontend '$frontendId' has invalid template output"
            return
        }
        val stripped = placeholderPattern.replace(output, "")
        if ('{' in stripped || '}' in stripped) {
            errors += "route '$routeId' frontend '$frontendId' has an unsupported template placeholder"
        }
    }

    private fun isPublicOrigin(origin: String): Boolean {
        val host = URI(origin).host?.lowercase()
        return host?.let { value -> !isLocalHost(value) && isPublicHost(value) } ?: false
    }

    private fun isLocalHost(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")

    private fun isPublicHost(host: String): Boolean =
        if (!ipv4Pattern.matches(host)) {
            true
        } else {
            val octets = host.split('.').map(String::toInt)
            val first = octets[0]
            val second = octets[1]
            val isValidAddress = octets.all { it in MIN_IPV4_OCTET..MAX_IPV4_OCTET }
            val isLinkLocal = first == LINK_LOCAL_FIRST_OCTET && second == LINK_LOCAL_SECOND_OCTET
            val isPrivateNetwork =
                first == PRIVATE_NETWORK_FIRST_OCTET && second in PRIVATE_NETWORK_START..PRIVATE_NETWORK_END
            val isPrivateClassC = first == PRIVATE_CLASS_C_FIRST_OCTET && second == PRIVATE_CLASS_C_SECOND_OCTET
            isValidAddress && first !in setOf(UNSPECIFIED_OCTET, PRIVATE_CLASS_A_OCTET, LOOPBACK_OCTET) &&
                !isLinkLocal &&
                !isPrivateNetwork &&
                !isPrivateClassC
        }
}
