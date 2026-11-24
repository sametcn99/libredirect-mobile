package dev.libredirect.mobile.core.manifest

import kotlinx.serialization.Serializable

@Serializable
data class Frontend(
    val id: String,
    val name: String,
    val strategy: Strategy,
    val instances: List<String> = emptyList(),
) {
    init {
        require(strategy is Strategy.ReplaceOrigin || Strategy.requiresInstances(strategy) == instances.isNotEmpty()) {
            "Frontend '$id': invalid instance list for ${strategy::class.simpleName}"
        }
    }
}
