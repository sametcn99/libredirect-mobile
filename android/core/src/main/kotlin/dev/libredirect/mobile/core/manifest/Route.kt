package dev.libredirect.mobile.core.manifest

import kotlinx.serialization.Serializable

@Serializable
data class Route(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val frontends: List<Frontend>,
    /** Optional anchored hostname patterns for upstream services with wildcard domains. */
    val hostPatterns: List<String> = emptyList(),
) {
    init {
        require(hosts.isNotEmpty() || hostPatterns.isNotEmpty()) {
            "Route '$id' must declare at least one host or host pattern"
        }
        require(frontends.isNotEmpty()) { "Route '$id' must declare at least one frontend" }
    }
}
