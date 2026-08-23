package dev.libredirect.mobile.core.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The fixed, allowlisted set of routing primitives a manifest may use.
 * There is no expression/eval variant: adding a new primitive requires an
 * app release, by design (see the project plan, "no remote code execution").
 */
@Serializable
sealed interface Strategy {
    @Serializable
    @SerialName("replace-origin")
    data object ReplaceOrigin : Strategy

    @Serializable
    @SerialName("template")
    data class Template(val output: String) : Strategy

    @Serializable
    @SerialName("custom-scheme")
    data class CustomScheme(val scheme: String) : Strategy

    @Serializable
    @SerialName("passthrough")
    data object Passthrough : Strategy

    companion object {
        fun requiresInstances(strategy: Strategy): Boolean =
            strategy is ReplaceOrigin || strategy is Template
    }
}
