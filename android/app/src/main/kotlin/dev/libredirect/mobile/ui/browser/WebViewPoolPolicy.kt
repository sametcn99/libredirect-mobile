package dev.libredirect.mobile.ui.browser

/**
 * LRU eviction rule for the browser's WebView pool (B10), extracted so the
 * victim selection is unit-testable: when more than [maxAliveWebViews] tabs
 * hold a WebView, the least-recently-touched *inactive* tab is evicted; the
 * active tab is never a victim, and tabs without a touch timestamp count as
 * the oldest.
 */
internal object WebViewPoolPolicy {
    /** The tab whose WebView should be destroyed next, or null when the pool is within its cap. */
    fun evictionCandidate(
        pooledTabIds: Set<String>,
        touchedAt: Map<String, Long>,
        activeTabId: String,
        maxAliveWebViews: Int,
    ): String? {
        if (pooledTabIds.size <= maxAliveWebViews) return null
        return pooledTabIds
            .filterNot { it == activeTabId }
            .minByOrNull { touchedAt[it] ?: 0L }
    }
}
