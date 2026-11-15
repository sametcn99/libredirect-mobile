package dev.libredirect.mobile.diagnostics

import dev.libredirect.mobile.core.routing.RoutingFailure
import dev.libredirect.mobile.core.routing.RoutingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingOutcomeLogTest {
    @Test
    fun `redirect outcome records host route and instance without the path`() {
        RoutingOutcomeLog.clear()
        RoutingOutcomeLog.record(
            "https://www.instagram.com/reel/DTqr4mTElos/?hl=en",
            RoutingResult.Redirect(
                originalUrl = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en",
                redirectedUrl = "https://example.org/reel/DTqr4mTElos",
                routeId = "instagram",
                frontendId = "proxigram",
                instance = "example.org",
            ),
        )
        val text = RoutingOutcomeLog.asText()
        assertTrue(text.contains("www.instagram.com -> REDIRECT"))
        assertTrue(text.contains("route=instagram"))
        assertTrue(text.contains("instance=example.org"))
        assertEquals(false, text.contains("reel"))
    }

    @Test
    fun `passthrough outcome records only the host`() {
        RoutingOutcomeLog.clear()
        RoutingOutcomeLog.record(
            "https://example.org/some/path?q=1",
            RoutingResult.Passthrough("https://example.org/some/path?q=1"),
        )
        val text = RoutingOutcomeLog.asText()
        assertTrue(text.contains("example.org -> PASSTHROUGH"))
        assertEquals(false, text.contains("some"))
    }

    @Test
    fun `failure outcome includes the routing failure reason`() {
        RoutingOutcomeLog.clear()
        RoutingOutcomeLog.record(
            "https://example.org/watch?v=1",
            RoutingResult.Failure("https://example.org/watch?v=1", RoutingFailure.NO_INSTANCE_AVAILABLE),
        )
        val text = RoutingOutcomeLog.asText()
        assertTrue(text.contains("example.org -> FAILURE"))
        assertTrue(text.contains("reason=NO_INSTANCE_AVAILABLE"))
    }

    @Test
    fun `unparseable urls are recorded as such instead of crashing`() {
        RoutingOutcomeLog.clear()
        RoutingOutcomeLog.record("not a url at all", RoutingResult.Passthrough("not a url at all"))
        assertTrue(RoutingOutcomeLog.asText().contains("(unparseable)"))
    }

    @Test
    fun `buffer keeps only the newest entries`() {
        RoutingOutcomeLog.clear()
        val buffer = OutcomeBuffer(capacity = 2)
        buffer.record(RoutingOutcomeLog.Entry(1L, "a.example", "REDIRECT", null))
        buffer.record(RoutingOutcomeLog.Entry(2L, "b.example", "PASSTHROUGH", null))
        buffer.record(RoutingOutcomeLog.Entry(3L, "c.example", "REDIRECT", null))
        val hosts = buffer.snapshot().map { it.host }
        assertEquals(listOf("b.example", "c.example"), hosts)
    }

    @Test
    fun `empty log renders a placeholder`() {
        val buffer = OutcomeBuffer(capacity = 5)
        assertEquals("(none recorded this session)", buffer.asText())
    }
}
