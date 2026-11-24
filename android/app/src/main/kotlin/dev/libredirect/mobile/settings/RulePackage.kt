package dev.libredirect.mobile.settings

import dev.libredirect.mobile.core.routing.InstanceSelection
import kotlinx.serialization.Serializable

data class BackupOptions(
    val settings: Boolean = true,
    val customRoutes: Boolean = true,
    val bookmarks: Boolean = false,
    val serviceIds: Set<String>? = null,
)

/** The portable subset is limited to selected services and has no browsing data. */
@Serializable
data class RulePackage(
    val frontends: Map<String, String> = emptyMap(),
    val instanceSelections: Map<String, InstanceSelection> = emptyMap(),
    val policies: Map<String, RouteLinkPolicy> = emptyMap(),
) {
    companion object {
        fun fromSettings(
            settings: AppSettings,
            serviceIds: Set<String>,
        ): RulePackage =
            RulePackage(
                frontends = settings.selectedFrontends.filterKeys { it in serviceIds },
                instanceSelections = settings.instanceSelections.filterKeys { it.substringBefore('/') in serviceIds },
                policies = settings.linkPolicies.routes.filterKeys { it in serviceIds },
            )
    }
}
