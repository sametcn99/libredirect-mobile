package dev.libredirect.mobile.update

/**
 * Pure decision logic for the update-check throttle, kept free of Android
 * and DataStore dependencies so the timing semantics are unit-testable.
 */
object UpdateThrottle {
    /** Minimum gap between automatic update checks (manual checks bypass it). */
    const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** After this many consecutive failed checks, one interval is skipped to avoid a request on every launch. */
    const val FAILURE_BACKOFF_THRESHOLD = 3

    fun shouldAutoCheck(
        lastCheckAt: Long,
        nowMillis: Long,
    ): Boolean = nowMillis - lastCheckAt >= AUTO_CHECK_INTERVAL_MS

    /**
     * Called when a check failed. Normally failures do not advance the
     * throttle (the next launch retries immediately); once failures pile up,
     * advancing it spares an unreachable GitHub a request per launch.
     * Returns the timestamp to record, or null to leave the old one alone.
     */
    fun backoffTimestamp(
        failedChecksSinceLastSuccess: Int,
        nowMillis: Long,
    ): Long? = if (failedChecksSinceLastSuccess >= FAILURE_BACKOFF_THRESHOLD) nowMillis else null
}
