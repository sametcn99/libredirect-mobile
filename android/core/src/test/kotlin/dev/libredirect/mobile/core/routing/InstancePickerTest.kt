package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InstancePickerTest {
    private val frontend =
        Frontend(
            id = "invidious",
            name = "Invidious",
            strategy = Strategy.ReplaceOrigin,
            instances = listOf("https://a.example", "https://b.example", "https://c.example"),
        )

    @Test
    fun `automatic picks from the manifest instance list`() {
        val picker = InstancePicker(Random(seed = 42))
        val picked = picker.pick(frontend, InstanceSelection.Automatic)
        assertTrue(picked in frontend.instances)
    }

    @Test
    fun `pinned instance still in the manifest list is used as-is`() {
        val picker = InstancePicker()
        val picked = picker.pick(frontend, InstanceSelection.Pinned("https://b.example"))
        assertEquals("https://b.example", picked)
    }

    @Test
    fun `stale pinned instance falls back to automatic`() {
        val picker = InstancePicker(Random(seed = 1))
        val picked = picker.pick(frontend, InstanceSelection.Pinned("https://removed.example"))
        assertTrue(picked in frontend.instances)
    }

    @Test
    fun `valid custom instance bypasses the manifest list entirely`() {
        val picker = InstancePicker()
        val picked = picker.pick(frontend, InstanceSelection.Custom("https://self-hosted.example"))
        assertEquals("https://self-hosted.example", picked)
    }

    @Test
    fun `malformed custom instance falls back to automatic`() {
        val picker = InstancePicker(Random(seed = 7))
        val picked = picker.pick(frontend, InstanceSelection.Custom("not-a-url"))
        assertTrue(picked in frontend.instances)
    }

    @Test
    fun `empty instance list yields null regardless of mode`() {
        val instanceless =
            Frontend(id = "official", name = "Official Site", strategy = Strategy.Passthrough)
        val picker = InstancePicker()
        assertNull(picker.pick(instanceless, InstanceSelection.Automatic))
    }
}
