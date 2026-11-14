package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTraceTest {
    private val manifest =
        Manifest(
            schemaVersion = 1,
            revision = 1,
            generatedAt = "2026-11-07T00:00:00Z",
            routes =
                listOf(
                    Route(
                        id = "youtube",
                        name = "YouTube",
                        hosts = listOf("youtube.com"),
                        frontends =
                            listOf(
                                Frontend(
                                    id = "invidious",
                                    name = "Invidious",
                                    strategy = Strategy.ReplaceOrigin,
                                    instances = listOf("https://yewtu.be"),
                                ),
                            ),
                    ),
                ),
        )

    @Test
    fun `redirect trace reports route frontend mode instance and stripped params`() {
        val trace =
            UrlRouter(manifest).explain(
                "https://youtube.com/watch?v=abc&si=xyz&utm_source=s",
                RoutingContext(instanceSelections = mapOf("youtube/invidious" to InstanceSelection.Fastest)),
            )
        assertEquals(RoutingOutcome.REDIRECT, trace.outcome)
        assertEquals("youtube", trace.routeId)
        assertEquals("invidious", trace.frontendId)
        assertEquals("fastest", trace.selectionMode)
        assertEquals("https://yewtu.be", trace.instance)
        assertEquals("https://yewtu.be/watch?v=abc", trace.resultUrl)
        assertEquals(2, trace.strippedParams)
    }

    @Test
    fun `no-route passthrough explains itself`() {
        val trace = UrlRouter(manifest).explain("https://unknown.example/", RoutingContext())
        assertEquals(RoutingOutcome.PASSTHROUGH, trace.outcome)
        assertEquals(TraceReason.NO_ROUTE, trace.reason)
    }

    @Test
    fun `exception and disabled and override traces`() {
        val router = UrlRouter(manifest)
        val exception =
            router.explain(
                "https://youtube.com/watch",
                RoutingContext(exceptions = listOf(ExceptionRule.Domain("youtube.com"))),
            )
        assertEquals(TraceReason.EXCEPTION_MATCHED, exception.reason)
        assertEquals("youtube.com", exception.detail)

        val disabled = router.explain("https://youtube.com/watch", RoutingContext(disabledRoutes = setOf("youtube")))
        assertEquals(TraceReason.ROUTE_DISABLED, disabled.reason)

        val override =
            router.explain(
                "https://youtube.com/watch",
                RoutingContext(instanceOverrides = mapOf("youtube/invidious" to "https://yewtu.be")),
            )
        assertEquals("override", override.selectionMode)
    }

    @Test
    fun `resolve and explain agree`() {
        val router = UrlRouter(manifest)
        val context = RoutingContext()
        val input = "https://youtube.com/watch?v=1"
        val result = router.resolve(input, context)
        val trace = router.explain(input, context)
        assertTrue(result is RoutingResult.Redirect)
        assertEquals((result as RoutingResult.Redirect).redirectedUrl, trace.resultUrl)
    }
}
