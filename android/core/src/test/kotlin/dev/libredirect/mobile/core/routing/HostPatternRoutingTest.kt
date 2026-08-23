package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPatternRoutingTest {
    private val manifest =
        Manifest(
            schemaVersion = 1,
            revision = 1,
            generatedAt = "2026-08-23T18:00:00Z",
            routes =
                listOf(
                    Route(
                        id = "wildcard-service",
                        name = "Wildcard service",
                        hosts = emptyList(),
                        hostPatterns = listOf("^([a-z]+\\.)?example\\.com$"),
                        frontends =
                            listOf(
                                Frontend(
                                    id = "privacy",
                                    name = "Privacy frontend",
                                    strategy = Strategy.ReplaceOrigin,
                                    instances = listOf("https://privacy.example"),
                                ),
                            ),
                    ),
                ),
        )

    @Test
    fun `wildcard hostname patterns route subdomains`() {
        val result = UrlRouter(manifest).resolve("https://news.example.com/article/1", RoutingContext())

        assertTrue(result is RoutingResult.Redirect)
        assertEquals("https://privacy.example/article/1", (result as RoutingResult.Redirect).redirectedUrl)
    }
}
