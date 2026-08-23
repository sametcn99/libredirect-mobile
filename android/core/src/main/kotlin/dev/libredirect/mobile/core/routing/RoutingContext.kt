package dev.libredirect.mobile.core.routing

/**
 * Per-resolve() input assembled by the Android layer from DataStore
 * settings. Whether routing is enabled at all is an Android-layer decision;
 * disabled individual routes are represented in [disabledRoutes].
 */
data class RoutingContext(
    val exceptions: List<ExceptionRule> = emptyList(),
    /** Route ids disabled by the user. Disabled routes pass the original URL through. */
    val disabledRoutes: Set<String> = emptySet(),
    /** routeId -> frontendId. Missing entry defaults to the route's first frontend. */
    val selectedFrontends: Map<String, String> = emptyMap(),
    /** "$routeId/$frontendId" -> selection. Missing entry defaults to Automatic. */
    val instanceSelections: Map<String, InstanceSelection> = emptyMap(),
) {
    fun instanceSelectionFor(
        routeId: String,
        frontendId: String,
    ): InstanceSelection = instanceSelections["$routeId/$frontendId"] ?: InstanceSelection.Automatic
}
