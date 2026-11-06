package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InstanceHealthTest {
    @Test
    fun `no failure means no cooldown`() {
        assertFalse(InstanceHealth().isCoolingDown(nowMillis = 1_000_000L))
    }

    @Test
    fun `recent failure enters cooldown for the window`() {
        val failedAt = 1_000_000L
        val health = InstanceHealth(lastFailedAtMillis = failedAt)
        assertTrue(health.isCoolingDown(failedAt + InstanceHealth.COOLDOWN_MILLIS - 1))
        assertFalse(health.isCoolingDown(failedAt + InstanceHealth.COOLDOWN_MILLIS))
    }

    @Test
    fun `faster instances carry more weight`() {
        val fast = InstanceHealth(latencyMillis = 100).selectionWeight
        val slow = InstanceHealth(latencyMillis = 4000).selectionWeight
        val unmeasured = InstanceHealth().selectionWeight
        val failed = InstanceHealth(latencyMillis = -1).selectionWeight
        assertTrue(fast > unmeasured)
        assertTrue(unmeasured > slow)
        assertTrue(slow > failed)
    }
}

class FastestAndCooldownSelectionTest {
    private val frontend =
        Frontend(
            id = "f",
            name = "F",
            strategy = Strategy.ReplaceOrigin,
            instances = listOf("https://a.example", "https://b.example", "https://c.example"),
        )

    @Test
    fun `fastest picks the lowest measured latency`() {
        val health =
            mapOf(
                "https://a.example" to InstanceHealth(latencyMillis = 500),
                "https://b.example" to InstanceHealth(latencyMillis = 120),
                "https://c.example" to InstanceHealth(latencyMillis = 900),
            )
        val picked = InstancePicker().pick(frontend, InstanceSelection.Fastest, health, nowMillis = 0)
        assertEquals("https://b.example", picked)
    }

    @Test
    fun `fastest prefers measured over unmeasured and never picks failed`() {
        val health =
            mapOf(
                "https://a.example" to InstanceHealth(latencyMillis = InstanceHealth.LATENCY_FAILED),
                "https://c.example" to InstanceHealth(latencyMillis = 900),
            )
        val picked = InstancePicker().pick(frontend, InstanceSelection.Fastest, health, nowMillis = 0)
        assertEquals("https://c.example", picked)
    }

    @Test
    fun `cooling-down instances are excluded from automatic picking`() {
        val now = 10_000_000L
        val health =
            mapOf(
                "https://a.example" to InstanceHealth(lastFailedAtMillis = now - 1),
                "https://b.example" to InstanceHealth(lastFailedAtMillis = now - 1),
                "https://c.example" to InstanceHealth(latencyMillis = 300),
            )
        val picker = InstancePicker(Random(1))
        repeat(20) {
            assertEquals("https://c.example", picker.pick(frontend, InstanceSelection.Automatic, health, now))
        }
    }

    @Test
    fun `fail-open when every instance is cooling down`() {
        val now = 10_000_000L
        val health =
            frontend.instances.associateWith { InstanceHealth(lastFailedAtMillis = now - 1) }
        val picked = InstancePicker().pick(frontend, InstanceSelection.Automatic, health, now)
        assertTrue(picked in frontend.instances)
    }

    @Test
    fun `weighted random with uniform health stays within the instance list`() {
        val picker = InstancePicker(Random(42))
        repeat(30) {
            val picked = picker.pick(frontend, InstanceSelection.Automatic, emptyMap(), nowMillis = 0)
            assertTrue(picked in frontend.instances)
        }
    }

    @Test
    fun `a very fast instance is picked far more often than a very slow one`() {
        val health =
            mapOf(
                "https://a.example" to InstanceHealth(latencyMillis = 50),
                "https://b.example" to InstanceHealth(latencyMillis = 5000),
            )
        val picker = InstancePicker(Random(7))
        var fastPicks = 0
        repeat(2000) {
            if (picker.pick(frontend, InstanceSelection.Automatic, health, 0) == "https://a.example") fastPicks++
        }
        // Weight ratio 100:1 → fast should dominate by a wide, deterministic margin.
        assertTrue("expected fast instance to dominate, got $fastPicks/2000", fastPicks > 1800)
    }
}
