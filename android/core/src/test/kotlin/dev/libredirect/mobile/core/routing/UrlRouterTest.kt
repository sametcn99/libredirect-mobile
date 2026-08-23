package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.url.UrlValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRouterTest {
    private val manifest =
        Manifest(
            schemaVersion = 1,
            revision = 1,
            generatedAt = "2026-08-23T00:00:00Z",
            routes =
                listOf(
                    Route(
                        id = "youtube",
                        name = "YouTube",
                        hosts = listOf("youtube.com", "www.youtube.com", "youtu.be"),
                        frontends =
                            listOf(
                                Frontend(
                                    id = "invidious",
                                    name = "Invidious",
                                    strategy = Strategy.ReplaceOrigin,
                                    instances = listOf("https://yewtu.be"),
                                ),
                                Frontend(
                                    id = "official",
                                    name = "Official Site",
                                    strategy = Strategy.Passthrough,
                                ),
                            ),
                    ),
                    Route(
                        id = "reddit",
                        name = "Reddit",
                        hosts = listOf("reddit.com"),
                        frontends =
                            listOf(
                                Frontend(
                                    id = "redlib",
                                    name = "Redlib",
                                    strategy = Strategy.Template(output = "{instance}/search?q={query:q}"),
                                    instances = listOf("https://redlib.example.org"),
                                ),
                            ),
                    ),
                    Route(
                        id = "some-service",
                        name = "Some Service",
                        hosts = listOf("some-service.example"),
                        frontends =
                            listOf(
                                Frontend(
                                    id = "some-app",
                                    name = "Some App",
                                    strategy = Strategy.CustomScheme(scheme = "someapp"),
                                ),
                            ),
                    ),
                ),
        )

    private val router = UrlRouter(manifest)

    @Test
    fun `unmatched host passes through unchanged`() {
        val result = router.resolve("https://example.org/page", RoutingContext())
        assertEquals(RoutingResult.Passthrough("https://example.org/page"), result)
    }

    @Test
    fun `replace-origin preserves path query and fragment`() {
        val result =
            router.resolve(
                "https://youtube.com/watch?v=abc&t=10#comment",
                RoutingContext(),
            )
        assertEquals(
            RoutingResult.Redirect(
                originalUrl = "https://youtube.com/watch?v=abc&t=10#comment",
                redirectedUrl = "https://yewtu.be/watch?v=abc&t=10#comment",
                routeId = "youtube",
                frontendId = "invidious",
            ),
            result,
        )
    }

    @Test
    fun `replace-origin with no path or query`() {
        val result = router.resolve("https://youtu.be", RoutingContext())
        assertEquals(
            RoutingResult.Redirect(
                originalUrl = "https://youtu.be",
                redirectedUrl = "https://yewtu.be",
                routeId = "youtube",
                frontendId = "invidious",
            ),
            result,
        )
    }

    @Test
    fun `passthrough frontend keeps the original URL as an explicit routing choice`() {
        val context = RoutingContext(selectedFrontends = mapOf("youtube" to "official"))
        val result = router.resolve("https://youtube.com/watch?v=abc", context)
        assertEquals(RoutingResult.Passthrough("https://youtube.com/watch?v=abc"), result)
    }

    @Test
    fun `template strategy substitutes instance and query parameter`() {
        val result = router.resolve("https://reddit.com/search?q=hello+world", RoutingContext())
        val redirect = result as RoutingResult.Redirect
        assertEquals("https://redlib.example.org/search?q=hello+world", redirect.redirectedUrl)
    }

    @Test
    fun `template query placeholder is empty when parameter absent`() {
        val result = router.resolve("https://reddit.com/search", RoutingContext())
        val redirect = result as RoutingResult.Redirect
        assertEquals("https://redlib.example.org/search?q=", redirect.redirectedUrl)
    }

    @Test
    fun `malformed query escape does not crash template routing`() {
        val result = router.resolve("https://reddit.com/search?q=%ZZ", RoutingContext())
        assertTrue(result is RoutingResult.Failure)
        assertEquals(RoutingFailure.MALFORMED_URL, (result as RoutingResult.Failure).reason)
    }

    @Test
    fun `custom-scheme wraps the original URL and needs no instance`() {
        val result = router.resolve("https://some-service.example/item/1", RoutingContext())
        assertEquals(
            RoutingResult.Redirect(
                originalUrl = "https://some-service.example/item/1",
                redirectedUrl = "someapp://https://some-service.example/item/1",
                routeId = "some-service",
                frontendId = "some-app",
            ),
            result,
        )
    }

    @Test
    fun `domain exception short-circuits before routing`() {
        val context = RoutingContext(exceptions = listOf(ExceptionRule.Domain("youtube.com")))
        val result = router.resolve("https://youtube.com/watch?v=abc", context)
        assertEquals(RoutingResult.Passthrough("https://youtube.com/watch?v=abc"), result)
    }

    @Test
    fun `disabled route passes through unchanged`() {
        val context = RoutingContext(disabledRoutes = setOf("youtube"))
        val result = router.resolve("https://youtube.com/watch?v=abc", context)
        assertEquals(RoutingResult.Passthrough("https://youtube.com/watch?v=abc"), result)
    }

    @Test
    fun `url-prefix exception matches host plus path`() {
        val context =
            RoutingContext(
                exceptions = listOf(ExceptionRule.UrlPrefix("youtube.com/channel/")),
            )
        val result = router.resolve("https://youtube.com/channel/example", context)
        assertEquals(RoutingResult.Passthrough("https://youtube.com/channel/example"), result)
    }

    @Test
    fun `exception does not affect unrelated paths on the same host`() {
        val context =
            RoutingContext(
                exceptions = listOf(ExceptionRule.UrlPrefix("youtube.com/channel/")),
            )
        val result = router.resolve("https://youtube.com/watch?v=abc", context)
        assertTrue(result is RoutingResult.Redirect)
    }

    @Test
    fun `malformed url fails without a redirect`() {
        val result = router.resolve("not a url at all", RoutingContext())
        assertTrue(result is RoutingResult.Failure)
        assertEquals(RoutingFailure.MALFORMED_URL, (result as RoutingResult.Failure).reason)
    }

    @Test
    fun `unsupported scheme is rejected, not fail-opened`() {
        val result = router.resolve("javascript:alert(1)", RoutingContext())
        assertTrue(result is RoutingResult.Failure)
        assertEquals(RoutingFailure.UNSUPPORTED_SCHEME, (result as RoutingResult.Failure).reason)
    }

    @Test
    fun `file scheme is rejected`() {
        val result = router.resolve("file:///etc/passwd", RoutingContext())
        assertTrue(result is RoutingResult.Failure)
        assertEquals(RoutingFailure.UNSUPPORTED_SCHEME, (result as RoutingResult.Failure).reason)
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        val result =
            router.resolve(
                "https://youtube.com/" + "a".repeat(UrlValidation.MAX_INPUT_LENGTH),
                RoutingContext(),
            )
        assertTrue(result is RoutingResult.Failure)
        assertEquals(RoutingFailure.MALFORMED_URL, (result as RoutingResult.Failure).reason)
    }

    @Test
    fun `redirect loop protection - identical output passes through`() {
        val loopManifest =
            Manifest(
                schemaVersion = 1,
                revision = 1,
                generatedAt = "2026-08-23T00:00:00Z",
                routes =
                    listOf(
                        Route(
                            id = "same-origin",
                            name = "Same Origin",
                            hosts = listOf("instance.example"),
                            frontends =
                                listOf(
                                    Frontend(
                                        id = "self",
                                        name = "Self",
                                        strategy = Strategy.ReplaceOrigin,
                                        instances = listOf("https://instance.example"),
                                    ),
                                ),
                        ),
                    ),
            )
        val result = UrlRouter(loopManifest).resolve("https://instance.example/", RoutingContext())
        assertTrue(result is RoutingResult.Passthrough)
    }

    @Test
    fun `unselected frontend id falls back to first frontend`() {
        val context = RoutingContext(selectedFrontends = mapOf("youtube" to "does-not-exist"))
        val result = router.resolve("https://youtube.com/watch?v=abc", context)
        assertTrue(result is RoutingResult.Redirect)
        assertEquals("invidious", (result as RoutingResult.Redirect).frontendId)
    }
}
