package dev.libredirect.mobile.core.routing

sealed interface RoutingResult {
    data class Redirect(
        val originalUrl: String,
        val redirectedUrl: String,
        val routeId: String,
        val frontendId: String,
    ) : RoutingResult

    data class Passthrough(
        val url: String,
    ) : RoutingResult

    data class Failure(
        val url: String,
        val reason: RoutingFailure,
    ) : RoutingResult
}

/**
 * [UNSUPPORTED_SCHEME] and [MALFORMED_URL] mean [RoutingResult.Failure.url]
 * was never eligible to route in the first place — the caller must NOT
 * fail-open by opening it as-is. [NO_INSTANCE_AVAILABLE] means the URL was
 * a valid http(s) link but routing broke down internally; that's the one
 * case fail-open (§2.4 of the project plan) applies to, and the caller
 * should open [RoutingResult.Failure.url] unmodified.
 */
enum class RoutingFailure {
    UNSUPPORTED_SCHEME,
    MALFORMED_URL,
    NO_INSTANCE_AVAILABLE,
}
