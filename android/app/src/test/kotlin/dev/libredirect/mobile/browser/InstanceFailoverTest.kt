package dev.libredirect.mobile.browser

import dev.libredirect.mobile.core.routing.InstanceHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover policy (2.2): which failures justify an automatic retry on
 * another instance, and which instance to try next. Untested until now even
 * though the object was designed as pure logic for exactly this purpose.
 */
class InstanceFailoverTest {
    private val now = 1_000_000L

    @Test
    fun `only server errors and 429 are retryable`() {
        assertTrue(InstanceFailover.isRetryableHttpError(500))
        assertTrue(InstanceFailover.isRetryableHttpError(503))
        assertTrue(InstanceFailover.isRetryableHttpError(599))
        assertTrue(InstanceFailover.isRetryableHttpError(429))
        assertFalse(InstanceFailover.isRetryableHttpError(499))
        assertFalse(InstanceFailover.isRetryableHttpError(404))
        assertFalse(InstanceFailover.isRetryableHttpError(600))
        assertFalse(InstanceFailover.isRetryableHttpError(300))
    }

    @Test
    fun `bot interstitial titles are detected case-insensitively`() {
        assertTrue(InstanceFailover.looksLikeBotInterstitial("Just a moment..."))
        assertTrue(InstanceFailover.looksLikeBotInterstitial("Attention Required! | Cloudflare"))
        assertTrue(InstanceFailover.looksLikeBotInterstitial("DDoS protection by ..."))
        assertTrue(InstanceFailover.looksLikeBotInterstitial("Access denied"))
        assertFalse(InstanceFailover.looksLikeBotInterstitial("Instagram"))
        assertFalse(InstanceFailover.looksLikeBotInterstitial(null))
        assertFalse(InstanceFailover.looksLikeBotInterstitial(""))
    }

    @Test
    fun `next instance is the fastest remaining candidate`() {
        val health =
            mapOf(
                "a.example" to InstanceHealth(latencyMillis = 50),
                "b.example" to InstanceHealth(latencyMillis = 200),
                "c.example" to InstanceHealth(latencyMillis = 100),
            )
        assertEquals(
            "c.example",
            InstanceFailover.nextInstance(
                instances = listOf("a.example", "b.example", "c.example"),
                failedInstance = "a.example",
                attemptsDone = 0,
                health = health,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `no retry once the attempt budget is spent`() {
        assertNull(
            InstanceFailover.nextInstance(
                instances = listOf("a.example", "b.example"),
                failedInstance = "a.example",
                attemptsDone = InstanceFailover.MAX_ATTEMPTS,
                health = emptyMap(),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `no retry for a single-instance frontend`() {
        assertNull(
            InstanceFailover.nextInstance(
                instances = listOf("only.example"),
                failedInstance = "only.example",
                attemptsDone = 0,
                health = emptyMap(),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `cooling-down instances are skipped even when faster`() {
        val cooling = InstanceHealth(latencyMillis = 10, lastFailedAtMillis = now - 1_000L)
        val healthy = InstanceHealth(latencyMillis = 900)
        assertEquals(
            "slow.example",
            InstanceFailover.nextInstance(
                instances = listOf("failed.example", "fast-but-cooling.example", "slow.example"),
                failedInstance = "failed.example",
                attemptsDone = 0,
                health =
                    mapOf(
                        "fast-but-cooling.example" to cooling,
                        "slow.example" to healthy,
                    ),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `no candidate when every alternative is cooling down`() {
        val cooling = InstanceHealth(latencyMillis = 10, lastFailedAtMillis = now - 1_000L)
        assertNull(
            InstanceFailover.nextInstance(
                instances = listOf("a.example", "b.example"),
                failedInstance = null,
                attemptsDone = 0,
                health = mapOf("a.example" to cooling, "b.example" to cooling),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `cooldown expiry makes an instance eligible again`() {
        val expiredCooldown =
            InstanceHealth(
                latencyMillis = 10,
                lastFailedAtMillis = now - InstanceHealth.COOLDOWN_MILLIS - 1,
            )
        assertEquals(
            "recovered.example",
            InstanceFailover.nextInstance(
                instances = listOf("a.example", "recovered.example"),
                failedInstance = "a.example",
                attemptsDone = 0,
                health = mapOf("recovered.example" to expiredCooldown),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `unknown failed instance picks the fastest overall`() {
        val health =
            mapOf(
                "a.example" to InstanceHealth(latencyMillis = 300),
                "b.example" to InstanceHealth(latencyMillis = 80),
            )
        assertEquals(
            "b.example",
            InstanceFailover.nextInstance(
                instances = listOf("a.example", "b.example"),
                failedInstance = null,
                attemptsDone = 0,
                health = health,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `unmeasured instances rank last`() {
        val health = mapOf("measured.example" to InstanceHealth(latencyMillis = 5_000))
        assertEquals(
            "measured.example",
            InstanceFailover.nextInstance(
                instances = listOf("unmeasured.example", "measured.example"),
                failedInstance = "other.example",
                attemptsDone = 0,
                health = health,
                nowMillis = now,
            ),
        )
    }
}
