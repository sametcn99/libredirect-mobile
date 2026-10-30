package dev.libredirect.mobile.manifest

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstanceMergerTest {
    private fun baseManifest(): Manifest =
        Manifest(
            schemaVersion = 1,
            revision = 4,
            generatedAt = "2026-08-23T16:40:22.977Z",
            routes =
                listOf(
                    Route(
                        id = "youtube",
                        name = "YouTube",
                        hosts = listOf("youtube.com", "m.youtube.com"),
                        frontends =
                            listOf(
                                Frontend(
                                    id = "invidious",
                                    name = "Invidious",
                                    strategy = Strategy.ReplaceOrigin,
                                    instances = listOf("https://old.example"),
                                ),
                                Frontend(id = "original", name = "Original site", strategy = Strategy.Passthrough),
                            ),
                    ),
                ),
        )

    @Test
    fun `replaces instances of replace-origin frontends matched by id`() {
        val merged = InstanceMerger.merge(baseManifest(), mapOf("invidious" to listOf("https://inv.nadeko.net")))!!

        assertEquals(5, merged.revision)
        assertEquals(listOf("https://inv.nadeko.net"), merged.routes[0].frontends[0].instances)
    }

    @Test
    fun `never touches passthrough frontends`() {
        val upstream =
            mapOf(
                "invidious" to listOf("https://inv.nadeko.net"),
                "original" to listOf("https://original.example"),
            )
        val merged = InstanceMerger.merge(baseManifest(), upstream)!!

        assertEquals(0, merged.routes[0].frontends[1].instances.size)
    }

    @Test
    fun `keeps current instances when upstream is missing or unknown`() {
        val upstream =
            mapOf(
                "invidious" to listOf("https://inv.nadeko.net"),
                "unknown-frontend" to listOf("https://x.example"),
            )
        val merged = InstanceMerger.merge(baseManifest(), upstream)!!

        assertEquals(listOf("https://inv.nadeko.net"), merged.routes[0].frontends[0].instances)
        assertEquals(5, merged.revision)
    }

    @Test
    fun `returns null when upstream matches the current state exactly`() {
        val unchanged = mapOf("invidious" to listOf("https://old.example"))

        assertNull(InstanceMerger.merge(baseManifest(), unchanged))
    }

    @Test
    fun `increments revision and refreshes generatedAt`() {
        val merged = InstanceMerger.merge(baseManifest(), mapOf("invidious" to listOf("https://new.example")))!!

        assertEquals(5, merged.revision)
        assertNotEquals("2026-08-23T16:40:22.977Z", merged.generatedAt)
        assertEquals(1, merged.schemaVersion)
    }
}
