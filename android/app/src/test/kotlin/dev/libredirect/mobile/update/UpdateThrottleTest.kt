package dev.libredirect.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateThrottleTest {
    private val interval = UpdateThrottle.AUTO_CHECK_INTERVAL_MS

    @Test
    fun `auto check fires only after the interval has elapsed`() {
        val lastCheck = 1_000_000L
        assertFalse(UpdateThrottle.shouldAutoCheck(lastCheck, lastCheck))
        assertFalse(UpdateThrottle.shouldAutoCheck(lastCheck, lastCheck + interval - 1))
        assertTrue(UpdateThrottle.shouldAutoCheck(lastCheck, lastCheck + interval))
        assertTrue(UpdateThrottle.shouldAutoCheck(lastCheck, lastCheck + interval * 5))
    }

    @Test
    fun `one or two failures do not advance the throttle`() {
        assertNull(UpdateThrottle.backoffTimestamp(1, nowMillis = 5_000L))
        assertNull(UpdateThrottle.backoffTimestamp(UpdateThrottle.FAILURE_BACKOFF_THRESHOLD - 1, nowMillis = 5_000L))
    }

    @Test
    fun `three consecutive failures back off for one interval`() {
        assertEquals(
            5_000L,
            UpdateThrottle.backoffTimestamp(UpdateThrottle.FAILURE_BACKOFF_THRESHOLD, nowMillis = 5_000L),
        )
        assertEquals(
            5_000L,
            UpdateThrottle.backoffTimestamp(UpdateThrottle.FAILURE_BACKOFF_THRESHOLD + 7, nowMillis = 5_000L),
        )
    }
}
