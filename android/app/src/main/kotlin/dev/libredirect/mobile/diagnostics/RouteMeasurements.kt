package dev.libredirect.mobile.diagnostics

import kotlin.math.ceil

/** In-memory diagnostics for this process. No link, host or app identifier is kept. */
internal object RouteMeasurements {
    private const val SAMPLE_COUNT = 100
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val MEDIAN = 0.50
    private const val P95 = 0.95
    private var firstRoutingMillis: Long? = null
    private val warmRoutingMillis = ArrayDeque<Long>()
    private val outcomes = mutableMapOf<String, Int>()

    @Synchronized
    fun recordRouting(startedAtNanos: Long) {
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI
        if (firstRoutingMillis == null) {
            firstRoutingMillis = elapsedMillis
        } else {
            warmRoutingMillis.addLast(elapsedMillis)
            if (warmRoutingMillis.size > SAMPLE_COUNT) warmRoutingMillis.removeFirst()
        }
    }

    @Synchronized
    fun recordOutcome(outcome: String) {
        outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
    }

    @Synchronized
    fun summary(): String {
        val warm = warmRoutingMillis.sorted()
        return buildString {
            val first = firstRoutingMillis?.let { "$it ms" } ?: "pending"
            appendLine("Routing activity duration, first in process: $first")
            if (warm.isNotEmpty()) {
                appendLine("Warm (n=${warm.size}): p50 ${percentile(warm, MEDIAN)} ms, p95 ${percentile(warm, P95)} ms")
            }
            if (outcomes.isNotEmpty()) append("Delivery and WebView events: ${outcomes.toSortedMap()}")
        }
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long =
        sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]
}
