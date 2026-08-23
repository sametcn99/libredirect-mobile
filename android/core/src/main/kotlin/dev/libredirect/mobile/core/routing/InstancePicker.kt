package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.url.UrlValidation
import kotlin.random.Random

/**
 * Implements the Automatic / Pinned / Custom modes from the project plan
 * (§23). A stale [InstanceSelection.Pinned] value (no longer in the
 * manifest's instance list) and an invalid [InstanceSelection.Custom]
 * value both fall back to Automatic rather than failing routing outright.
 */
class InstancePicker(private val random: Random = Random.Default) {
    fun pick(
        frontend: Frontend,
        selection: InstanceSelection,
    ): String? {
        val instances = frontend.instances
        if (instances.isEmpty()) return null

        return when (selection) {
            is InstanceSelection.Automatic -> instances.random(random)
            is InstanceSelection.Pinned ->
                if (selection.instance in instances) selection.instance else instances.random(random)
            is InstanceSelection.Custom ->
                if (UrlValidation.isValidHttpsOrigin(selection.instance)) {
                    selection.instance
                } else {
                    instances.random(random)
                }
        }
    }
}
