package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.url.UrlValidation
import kotlin.random.Random

/**
 * Implements the Automatic / Fastest / Pinned / Custom modes from the project
 * plan (§23). A stale [InstanceSelection.Pinned] value (no longer in the
 * manifest's instance list) and an invalid [InstanceSelection.Custom] value
 * both fall back to Automatic rather than failing routing outright.
 *
 * Automatic is latency-weighted random among instances that are not cooling
 * down (2.1): fast instances are picked more often, but load stays spread.
 * When every instance is cooling down the cooldown is ignored (fail-open) —
 * picking nobody is never an option.
 */
class InstancePicker(private val random: Random = Random.Default) {
    fun pick(
        frontend: Frontend,
        selection: InstanceSelection,
        health: Map<String, InstanceHealth> = emptyMap(),
        nowMillis: Long = 0L,
    ): String? {
        val instances = frontend.instances

        return when (selection) {
            is InstanceSelection.Automatic -> pickWeighted(instances, health, nowMillis)
            is InstanceSelection.Fastest -> pickFastest(instances, health, nowMillis)
            is InstanceSelection.Pinned ->
                if (selection.instance in instances) selection.instance else pickWeighted(instances, health, nowMillis)
            is InstanceSelection.Custom ->
                if (UrlValidation.isValidHttpsOrigin(selection.instance)) {
                    selection.instance
                } else {
                    pickWeighted(instances, health, nowMillis)
                }
        }
    }

    private fun pickFastest(
        instances: List<String>,
        health: Map<String, InstanceHealth>,
        nowMillis: Long,
    ): String? =
        instances
            .filterNot { health[it]?.isCoolingDown(nowMillis) == true }
            .minByOrNull { instance ->
                val latency = health[instance]?.currentLatency(nowMillis)
                when {
                    latency == null -> Long.MAX_VALUE - 1
                    latency < 0 -> Long.MAX_VALUE
                    else -> latency
                }
            }

    /**
     * Latency-weighted random among healthy instances; when all are cooling
     * down the whole list becomes eligible (fail-open).
     */
    private fun pickWeighted(
        instances: List<String>,
        health: Map<String, InstanceHealth>,
        nowMillis: Long,
    ): String? {
        if (instances.isEmpty()) return null
        val healthy = instances.filterNot { instance -> health[instance]?.isCoolingDown(nowMillis) == true }
        val candidates = healthy.ifEmpty { instances }
        val weights =
            candidates.map { instance ->
                InstanceHealth(latencyMillis = health[instance]?.currentLatency(nowMillis)).selectionWeight
            }
        val total = weights.sum()
        return if (total <= 0.0) {
            candidates.random(random)
        } else {
            pickByDart(candidates, weights, random.nextDouble() * total)
        }
    }

    private fun pickByDart(
        candidates: List<String>,
        weights: List<Double>,
        dart: Double,
    ): String {
        var remaining = dart
        candidates.forEachIndexed { index, instance ->
            remaining -= weights[index]
            if (remaining <= 0.0) return instance
        }
        return candidates.last()
    }
}
