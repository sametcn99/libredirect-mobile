package dev.libredirect.mobile.core.routing

/**
 * Structured "why?" output for one resolve (2.8): which rule matched, which
 * frontend and instance were chosen, which parameters were stripped, and —
 * when the URL was not redirected — the reason it passed through. The UI
 * layer maps [reason] onto localized text; [detail] carries the raw value to
 * show next to it (a host, a rule value, an instance origin…).
 */
data class RoutingTrace(
    val input: String,
    val outcome: RoutingOutcome,
    val resultUrl: String? = null,
    val routeId: String? = null,
    val frontendId: String? = null,
    val selectionMode: String? = null,
    val instance: String? = null,
    val strippedParams: Int = 0,
    val reason: TraceReason = TraceReason.NONE,
    val detail: String? = null,
    /** Hosts unwrapped from redirect wrappers / AMP caches before routing (3.1). */
    val unwrappedLayers: Int = 0,
    /** True when the input host was a shortener that the app resolved (3.3). */
    val shortLinkResolved: Boolean = false,
)

enum class RoutingOutcome {
    REDIRECT,
    PASSTHROUGH,
    FAILURE,
}

enum class TraceReason {
    NONE,

    /** Nothing in the manifest matches this host. */
    NO_ROUTE,

    /** The user disabled this service. */
    ROUTE_DISABLED,

    /** A user exception rule matched. */
    EXCEPTION_MATCHED,

    /** The service is set to "ask every time"; nothing was redirected yet. */
    ASK_MODE,

    /** Routing is paused until a timestamp. */
    PAUSED,

    /** Routing master switch is off. */
    ROUTING_DISABLED,

    /** The frontend's strategy does not redirect (passthrough / custom scheme without instance). */
    STRATEGY_PASSTHROUGH,

    /** No instance could be selected. */
    NO_INSTANCE,

    /** The redirect target equals the input. */
    TARGET_UNCHANGED,

    /** Input was never a valid eligible URL. */
    NOT_ELIGIBLE,

    /** The frontend requires instances but none are healthy. */
    INSTANCE_COOLDOWN,
}
