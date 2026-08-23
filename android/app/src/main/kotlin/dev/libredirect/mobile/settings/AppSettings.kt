package dev.libredirect.mobile.settings

import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.routing.RoutingContext

data class AppSettings(
    val routingEnabled: Boolean = true,
    val selectedBrowserPackage: String? = null,
    /** Route ids the user has turned off; a route not in this set is enabled by default. */
    val disabledRoutes: Set<String> = emptySet(),
    /** routeId -> frontendId. Missing entry defaults to the route's first frontend. */
    val selectedFrontends: Map<String, String> = emptyMap(),
    /** "$routeId/$frontendId" -> selection. Missing entry defaults to Automatic. */
    val instanceSelections: Map<String, InstanceSelection> = emptyMap(),
    val exceptions: List<ExceptionRule> = emptyList(),
    val manifestRevision: Int? = null,
    val manifestUpdatedAtEpochMillis: Long? = null,
) {
    fun isRouteEnabled(routeId: String): Boolean = routeId !in disabledRoutes

    fun routingContext(): RoutingContext =
        RoutingContext(
            exceptions = exceptions,
            disabledRoutes = disabledRoutes,
            selectedFrontends = selectedFrontends,
            instanceSelections = instanceSelections,
        )
}
