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
        val requiresInstances = Strategy.requiresInstances(strategy)
        require(requiresInstances == instances.isNotEmpty()) {
            "Frontend '$id': ${strategy::class.simpleName} requires instances=$requiresInstances, got ${instances.size}"
        }
    }
}
