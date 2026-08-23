package dev.libredirect.mobile.core.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User preference for how a frontend's instance is chosen. Mirrors the
 * project plan's "Automatic / Pinned / Custom" modes (§23). This is app
 * settings state (DataStore-backed), never part of the remote manifest.
 */
@Serializable
sealed interface InstanceSelection {
    @Serializable
    @SerialName("automatic")
    data object Automatic : InstanceSelection

    @Serializable
    @SerialName("pinned")
    data class Pinned(val instance: String) : InstanceSelection

    @Serializable
    @SerialName("custom")
    data class Custom(val instance: String) : InstanceSelection
}
