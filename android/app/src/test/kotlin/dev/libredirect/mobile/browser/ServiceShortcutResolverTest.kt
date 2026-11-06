package dev.libredirect.mobile.browser

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.routing.InstanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceShortcutResolverTest {
    private val route =
        Route(
            id = "youtube",
            name = "YouTube",
            hosts = listOf("youtube.com"),
            frontends =
                listOf(
                    Frontend(
                        id = "piped",
                        name = "Piped",
                        strategy = Strategy.ReplaceOrigin,
                        instances = listOf("https://piped.video", "https://piped.privacydev.net"),
                    ),
                    Frontend(
                        id = "invidious",
                        name = "Invidious",
                        strategy = Strategy.ReplaceOrigin,
                        instances = listOf("https://yewtu.be"),
                    ),
                ),
        )

    @Test
    fun `null when the route is unknown`() {
        assertNull(ServiceShortcutResolver.resolveInstanceUrl(null, emptyMap(), emptyMap()))
    }

    @Test
    fun `automatic selection falls back to the first instance of the first frontend`() {
        assertEquals(
            "https://piped.video",
            ServiceShortcutResolver.resolveInstanceUrl(route, emptyMap(), emptyMap()),
        )
    }

    @Test
    fun `explicit frontend choice is honored before automatic instance`() {
        assertEquals(
            "https://yewtu.be",
            ServiceShortcutResolver.resolveInstanceUrl(
                route,
                selectedFrontends = mapOf("youtube" to "invidious"),
                instanceSelections = emptyMap(),
            ),
        )
    }

    @Test
    fun `pinned instance wins over the automatic fallback`() {
        val selection = mapOf("youtube/piped" to InstanceSelection.Pinned("https://piped.privacydev.net"))
        assertEquals(
            "https://piped.privacydev.net",
            ServiceShortcutResolver.resolveInstanceUrl(route, emptyMap(), selection),
        )
    }

    @Test
    fun `custom instance is used as-is`() {
        val selection = mapOf("youtube/piped" to InstanceSelection.Custom("https://my.piped.example"))
        assertEquals(
            "https://my.piped.example",
            ServiceShortcutResolver.resolveInstanceUrl(route, emptyMap(), selection),
        )
    }

    @Test
    fun `an unknown selected frontend falls back to the first frontend`() {
        assertEquals(
            "https://piped.video",
            ServiceShortcutResolver.resolveInstanceUrl(
                route,
                selectedFrontends = mapOf("youtube" to "vanished"),
                instanceSelections = emptyMap(),
            ),
        )
    }

    @Test
    fun `null when the resolved frontend has no instances`() {
        val passthrough =
            Route(
                id = "example",
                name = "Example",
                hosts = listOf("example.org"),
                frontends = listOf(Frontend(id = "original", name = "Original site", strategy = Strategy.Passthrough)),
            )
        assertNull(ServiceShortcutResolver.resolveInstanceUrl(passthrough, emptyMap(), emptyMap()))
    }
}
