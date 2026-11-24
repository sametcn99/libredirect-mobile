package dev.libredirect.mobile.settings

import dev.libredirect.mobile.core.routing.RoutingTrace
import dev.libredirect.mobile.core.routing.TraceReason
import kotlinx.serialization.Serializable

@Serializable
enum class OriginalFallback { OPEN, ASK, BLOCK }

@Serializable
data class RouteLinkPolicy(
    val targetPackage: String? = null,
    val fallback: OriginalFallback = OriginalFallback.OPEN,
)

@Serializable
data class LinkPolicies(
    val routes: Map<String, RouteLinkPolicy> = emptyMap(),
    val hosts: Map<String, HostLinkPolicy> = emptyMap(),
    val defaultFallback: OriginalFallback = OriginalFallback.OPEN,
) {
    fun forHost(host: String): HostLinkPolicy? =
        hosts.entries.filter { (domain, _) -> host == domain || host.endsWith(".$domain") }
            .maxByOrNull { (domain, _) -> domain.length }?.value

    fun targetPackage(
        host: String?,
        routeId: String?,
        global: String?,
    ): String? = host?.let(::forHost)?.targetPackage ?: routes[routeId]?.targetPackage ?: global

    fun fallback(trace: RoutingTrace): OriginalFallback? =
        trace.reason.takeIf { trace.routeId != null && it in unavailable }?.let {
            routes[trace.routeId]?.fallback ?: defaultFallback
        }

    companion object {
        private val unavailable =
            setOf(
                TraceReason.NO_INSTANCE,
                TraceReason.INSTANCE_COOLDOWN,
                TraceReason.UNSUPPORTED_LINK_SHAPE,
                TraceReason.STRATEGY_PASSTHROUGH,
                TraceReason.TARGET_UNCHANGED,
                TraceReason.SIGNED_QUERY,
            )
    }
}

@Serializable
data class HostLinkPolicy(
    val targetPackage: String? = null,
    val preserveParameters: Set<String> = emptySet(),
    val skipExtraCleanup: Boolean = false,
    val skipWrapperUnwrap: Boolean = false,
)

enum class RoutingPreset(val route: Boolean, val extraCleanup: Boolean, val fallback: OriginalFallback) {
    BALANCED(true, false, OriginalFallback.OPEN),
    STRICT(true, true, OriginalFallback.BLOCK),
    CLEAN_ONLY(false, false, OriginalFallback.OPEN),
}
