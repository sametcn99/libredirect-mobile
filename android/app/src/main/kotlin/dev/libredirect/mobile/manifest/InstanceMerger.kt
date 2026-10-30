package dev.libredirect.mobile.manifest

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Strategy
import java.time.Instant

/**
 * Merges freshly fetched upstream instance lists into a base manifest.
 * Only the per-frontend `instances` arrays change — routes, hosts and
 * frontend structure are frozen at build time (new services/frontends
 * arrive with app releases, matching the upstream browser extension's
 * model). Frontends whose strategy does not take instances, and frontends
 * with no non-empty upstream entry, keep their current list so the
 * `Frontend` instance invariant is never violated.
 */
object InstanceMerger {
    /**
     * @return the merged manifest with an incremented revision and a fresh
     * `generatedAt`, or null when the merge would change nothing (the
     * upstream data matches the base manifest exactly).
     */
    fun merge(
        base: Manifest,
        instanceData: Map<String, List<String>>,
    ): Manifest? {
        var changed = false
        val routes =
            base.routes.map { route ->
                val frontends =
                    route.frontends.map { frontend ->
                        val upstream = instanceData[frontend.id]
                        if (isRefreshable(frontend) && upstream != null && upstream != frontend.instances) {
                            changed = true
                            frontend.copy(instances = upstream)
                        } else {
                            frontend
                        }
                    }
                if (frontends != route.frontends) route.copy(frontends = frontends) else route
            }
        if (!changed) return null
        return base.copy(revision = base.revision + 1, generatedAt = Instant.now().toString(), routes = routes)
    }

    /** ReplaceOrigin/Template require instances; Passthrough/CustomScheme must not declare any. */
    private fun isRefreshable(frontend: Frontend): Boolean = Strategy.requiresInstances(frontend.strategy)
}
