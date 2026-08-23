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

    private val idPattern = Regex("^[a-z][a-z0-9-]{0,63}$")
    private val hostnamePattern =
        Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val instancePattern =
        Regex("^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{1,5})?$")
    private val schemePattern = Regex("^[a-z][a-z0-9+.-]{1,31}$")
    private val placeholderPattern = Regex("\\{(instance|path|fragment|query:[A-Za-z0-9_]+)\\}")
    private val boundedQuantifierPattern = Regex("\\{[0-9]{1,2}(,[0-9]{1,2})?\\}")
    private val ipv4Pattern = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

    fun validate(manifest: Manifest): List<String> {
        val errors = mutableListOf<String>()
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

        val routeIds = mutableSetOf<String>()
        val hosts = mutableSetOf<String>()
        if (manifest.routes.size > 2_000) errors += "too many routes"
        for (route in manifest.routes) {
            if (!idPattern.matches(route.id)) errors += "invalid route id '${route.id}'"
            if (!routeIds.add(route.id)) errors += "duplicate route id '${route.id}'"
            if (route.name.isBlank() || route.name.length > 80) errors += "route '${route.id}' has an invalid name"
            if (route.hosts.size > 32 || route.hosts.size + route.hostPatterns.size > 64) {
                errors += "route '${route.id}' has an invalid host count"
            }
            if (route.hosts.isEmpty() && route.hostPatterns.isEmpty()) {
                errors += "route '${route.id}' has no host matchers"
            }
            if (route.hostPatterns.size > 16) {
                errors += "route '${route.id}' has too many host patterns"
            }
            if (route.frontends.isEmpty() || route.frontends.size > 16) {
                errors += "route '${route.id}' has an invalid frontend count"
            }

            for (host in route.hosts) {
                if (!hostnamePattern.matches(host) || host != host.lowercase()) {
                    errors += "route '${route.id}' has invalid host '$host'"
                }
                if (!hosts.add(host)) errors += "duplicate host '$host'"
            }
            for (pattern in route.hostPatterns) {
                validateHostPattern(route.id, pattern, errors)
            }

            val frontendIds = mutableSetOf<String>()
            for (frontend in route.frontends) {
                if (!idPattern.matches(frontend.id)) {
                    errors += "route '${route.id}' has invalid frontend id '${frontend.id}'"
                }
                if (!frontendIds.add(frontend.id)) {
                    errors += "route '${route.id}' has duplicate frontend id '${frontend.id}'"
                }
                validateFrontend(route.id, frontend, errors)
            }
        }
        return errors
    }

    private fun validateHostPattern(
        routeId: String,
        pattern: String,
        errors: MutableList<String>,
    ) {
        if (pattern.length > 256 || pattern.any { it.code < 0x20 } || !pattern.startsWith("^") || !pattern.endsWith("$")) {
            errors += "route '$routeId' has an invalid host pattern"
            return
        }
        // The generator emits hostname-only patterns. Reject constructs that can
        // introduce arbitrary URL matching or expensive backtracking.
        if (pattern.contains('/') || pattern.contains("\\\\") || pattern.contains("(?<") ||
            pattern.contains(".*") || pattern.contains(".+") ||
            pattern.contains("\\1") || pattern.contains("\\2") || pattern.contains("\\3") ||
            boundedQuantifierPattern.replace(pattern, "").let { it.contains("{") || it.contains("}") }
        ) {
            errors += "route '$routeId' has an unsafe host pattern"
            return
        }
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
        if (frontend.name.isBlank() || frontend.name.length > 80) {
            errors += "frontend '${frontend.id}' has an invalid name"
        }
        if (frontend.instances.size > 64) errors += "frontend '${frontend.id}' has too many instances"
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

        val uniqueInstances = mutableSetOf<String>()
        for (instance in frontend.instances) {
            if (!instancePattern.matches(instance) || !isPublicOrigin(instance)) {
                errors += "frontend '${frontend.id}' has invalid or private instance '$instance'"
            }
            if (!uniqueInstances.add(instance)) errors += "frontend '${frontend.id}' has duplicate instance '$instance'"
        }
    }

    private fun validateTemplate(
        routeId: String,
        frontendId: String,
        output: String,
        errors: MutableList<String>,
    ) {
        if (output.isBlank() || output.length > 512 || output.any { it.code < 0x20 }) {
            errors += "route '$routeId' frontend '$frontendId' has invalid template output"
            return
        }
        val stripped = placeholderPattern.replace(output, "")
        if ('{' in stripped || '}' in stripped) {
            errors += "route '$routeId' frontend '$frontendId' has an unsupported template placeholder"
        }
    }

    private fun isPublicOrigin(origin: String): Boolean {
        val host = URI(origin).host?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (!ipv4Pattern.matches(host)) return true
        val octets = host.split('.').map(String::toInt)
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return first !in setOf(0, 10, 127) &&
            !(first == 169 && second == 254) &&
            !(first == 172 && second in 16..31) &&
            !(first == 192 && second == 168)
    }
}
