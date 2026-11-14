package dev.libredirect.mobile.core.routing

/**
 * Per-resolve() input assembled by the Android layer from DataStore
 * settings. Whether routing is enabled at all is an Android-layer decision;
 * disabled individual routes are represented in [disabledRoutes].
 */
data class RoutingContext(
    val exceptions: List<ExceptionRule> = emptyList(),
    // Route ids disabled by the user. Disabled routes pass the original URL through.
    val disabledRoutes: Set<String> = emptySet(),
    // routeId -> frontendId. Missing entry defaults to the route's first frontend.
    val selectedFrontends: Map<String, String> = emptyMap(),
    // "$routeId/$frontendId" -> selection. Missing entry defaults to Automatic.
    val instanceSelections: Map<String, InstanceSelection> = emptyMap(),
    // "$routeId/$frontendId" -> explicit instance origin for a one-off resolve
    // (e.g. the browser's "try another instance" action). Takes precedence
    // over [instanceSelections]; values not present in the frontend's
    // instance list are ignored.
    val instanceOverrides: Map<String, String> = emptyMap(),
    /**
     * Measured per-instance health (latency, failure cooldown), keyed by
     * instance origin. Plain data: the routing core reads it, it never
     * measures or touches the network.
     */
    val instanceHealth: Map<String, InstanceHealth> = emptyMap(),
    /** Reference time for health/cooldown evaluation; the Android layer supplies the wall clock. */
    val nowMillis: Long = 0L,
    /** User-defined tracking parameters (global and host-scoped), merged into the built-in lists. */
    val customTrackingRules: CustomTrackingRules = CustomTrackingRules.NONE,
    /** Package name of the app that handed over the link (EXTRA_REFERRER), or null when unknown. */
    val sourceApp: String? = null,
) {
    fun instanceSelectionFor(
        routeId: String,
        frontendId: String,
    ): InstanceSelection = instanceSelections["$routeId/$frontendId"] ?: InstanceSelection.Automatic
}

/**
 * User-defined tracking parameter rules (3.2). [global] applies to every
 * host; [byHost] entries apply to their host and any subdomain. Names are
 * stored as typed in by the user and compared case-insensitively; a name
 * ending in `*` acts as a prefix match, so `ref_*` strips `ref_creative` etc.
 */
data class CustomTrackingRules(
    val global: Set<String> = emptySet(),
    val byHost: Map<String, Set<String>> = emptyMap(),
) {
    companion object {
        val NONE = CustomTrackingRules()
    }
}
