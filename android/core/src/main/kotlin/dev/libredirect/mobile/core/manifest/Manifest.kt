package dev.libredirect.mobile.core.manifest

import kotlinx.serialization.Serializable

/**
 * Deserializing a manifest only checks structure and type (this class's
 * `init` and its children's `init` blocks). It is not a substitute for the
 * full schema/semantic validation (id/host patterns, global host
 * uniqueness, HTTPS/private-host rules, size caps) that must already have
 * passed before a manifest is signed and activated.
 */
@Serializable
data class Manifest(
    val schemaVersion: Int,
    val revision: Int,
    val generatedAt: String,
    val routes: List<Route>,
) {
    init {
        require(revision >= 1) { "revision must be >= 1, got $revision" }
    }
}
