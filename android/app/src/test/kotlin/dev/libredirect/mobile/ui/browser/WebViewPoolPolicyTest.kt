package dev.libredirect.mobile.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewPoolPolicyTest {
    private val pool = setOf("a", "b", "c", "d")

    @Test
    fun `no victim while the pool is within its cap`() {
        assertNull(
            WebViewPoolPolicy.evictionCandidate(pool, touchedAt = emptyMap(), activeTabId = "a", maxAliveWebViews = 4),
        )
        assertNull(
            WebViewPoolPolicy.evictionCandidate(pool, touchedAt = emptyMap(), activeTabId = "a", maxAliveWebViews = 8),
        )
    }

    @Test
    fun `evicts the least recently touched inactive tab`() {
        val touchedAt = mapOf("a" to 500L, "b" to 400L, "c" to 300L, "d" to 200L)
        assertEquals("d", WebViewPoolPolicy.evictionCandidate(pool, touchedAt, activeTabId = "a", maxAliveWebViews = 3))
    }

    @Test
    fun `never evicts the active tab even if it is the oldest`() {
        val touchedAt = mapOf("a" to 100L, "b" to 400L, "c" to 300L, "d" to 200L)
        assertEquals("d", WebViewPoolPolicy.evictionCandidate(pool, touchedAt, activeTabId = "a", maxAliveWebViews = 3))
    }

    @Test
    fun `a tab without a touch timestamp counts as the oldest`() {
        val touchedAt = mapOf("a" to 100L, "b" to 400L, "c" to 300L)
        assertEquals("d", WebViewPoolPolicy.evictionCandidate(pool, touchedAt, activeTabId = "a", maxAliveWebViews = 3))
    }

    @Test
    fun `returns null when only the active tab could be evicted`() {
        val single = setOf("only")
        assertNull(
            WebViewPoolPolicy.evictionCandidate(
                single,
                touchedAt = mapOf("only" to 1L),
                activeTabId = "only",
                maxAliveWebViews = 0,
            ),
        )
    }
}
