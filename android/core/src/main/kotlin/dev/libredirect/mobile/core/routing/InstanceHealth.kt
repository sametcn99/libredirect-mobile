package dev.libredirect.mobile.core.routing

import kotlinx.serialization.Serializable

/**
 * Health state of a single instance origin, keyed by origin in
 * [RoutingContext.instanceHealth]. Plain data produced by the app layer
 * (latency probes and observed load failures); the routing core only reads
 * it and never performs any measurement or network activity itself.
 *
 * [latencyMillis] follows the app's probe convention: null when never
 * measured, -1 when the probe failed, otherwise time-to-first-byte in ms.
 */
@Serializable
data class InstanceHealth(
    val latencyMillis: Long? = null,
    /** Epoch millis of the most recent observed load failure, or null. */
    val lastFailedAtMillis: Long? = null,
) {
    /** True while the instance is serving its post-failure cooldown window. */
    fun isCoolingDown(nowMillis: Long): Boolean {
        val failedAt = lastFailedAtMillis ?: return false
        return nowMillis - failedAt < COOLDOWN_MILLIS
    }

    /**
     * Relative selection weight for latency-weighted random picking: faster
     * instances weigh more, unmeasured ones count as a neutral 1-second
     * probe, and a failed probe weighs the least. Only the ratio between
     * values matters.
     */
    val selectionWeight: Double
        get() =
            when {
                latencyMillis == null -> NEUTRAL_WEIGHT
                latencyMillis < 0 -> FAILED_WEIGHT
                else -> (WEIGHT_SCALE / latencyMillis.coerceAtLeast(1)).coerceAtLeast(FAILED_WEIGHT * 2)
            }

    companion object {
        /** How long an instance stays excluded after a failed load (2.1/2.2). */
        const val COOLDOWN_MILLIS = 10 * 60 * 1000L
        const val LATENCY_FAILED = -1L

        const val NEUTRAL_WEIGHT = 1.0
        const val FAILED_WEIGHT = 0.25
        const val WEIGHT_SCALE = 1000.0
    }
}
