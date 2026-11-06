package dev.libredirect.mobile.browser

import dev.libredirect.mobile.core.routing.InstanceHealth

/**
 * Pure failover decision logic (2.2), separated from the ViewModel so the
 * retry policy is unit-testable: a failed routed main-frame load may be
 * retried automatically on another healthy instance of the same frontend,
 * at most [MAX_ATTEMPTS] times, before the user is offered the original
 * site instead.
 */
object InstanceFailover {
    const val MAX_ATTEMPTS = 2

    private const val HTTP_SERVER_ERROR_START = 500
    private const val HTTP_SERVER_ERROR_END = 599
    private const val HTTP_TOO_MANY_REQUESTS = 429

    /** HTTP statuses that justify an automatic retry on another instance. */
    fun isRetryableHttpError(statusCode: Int): Boolean =
        statusCode in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END || statusCode == HTTP_TOO_MANY_REQUESTS

    /** Titles typical of anti-bot/Cloudflare interstitials (best-effort detection). */
    fun looksLikeBotInterstitial(pageTitle: String?): Boolean {
        if (pageTitle.isNullOrBlank()) return false
        val title = pageTitle.lowercase()
        return title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("ddos protection") ||
            title.contains("access denied")
    }

    /**
     * Whether another automatic attempt should be made for a tab whose
     * routed load just failed, and on which instance.
     *
     * @param instances the frontend's instance list, in manifest order.
     * @param failedInstance the instance that just failed (may be null when unknown).
     * @param attemptsDone automatic failover attempts already made for this load.
     * @param health current health map for cooldown filtering.
     * @param nowMillis wall clock for cooldown evaluation.
     * @return the next instance to try, or null when the limit is reached
     *   or no other candidate exists (the error page then offers the
     *   original site).
     */
    fun nextInstance(
        instances: List<String>,
        failedInstance: String?,
        attemptsDone: Int,
        health: Map<String, InstanceHealth>,
        nowMillis: Long,
    ): String? {
        val canRetry = attemptsDone < MAX_ATTEMPTS && instances.size >= 2
        val healthy =
            if (canRetry) {
                instances
                    .filterNot { instance -> instance == failedInstance }
                    .filterNot { instance -> health[instance]?.isCoolingDown(nowMillis) == true }
            } else {
                emptyList()
            }
        return healthy.minByOrNull { instance -> health[instance]?.latencyMillis ?: Long.MAX_VALUE }
    }
}
