package dev.libredirect.mobile.core.routing

interface Router {
    fun resolve(
        input: String,
        context: RoutingContext,
    ): RoutingResult
}
