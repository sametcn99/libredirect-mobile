package dev.libredirect.mobile.redirect

import dev.libredirect.mobile.core.manifest.ManifestJson
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.routing.UrlRouter
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.CustomTrackingParam
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinkPipelineTest {
    private val bundledManifest by lazy {
        val userDir = System.getProperty("user.dir").orEmpty()
        val candidates =
            generateSequence(File(userDir)) { it.parentFile }
                .map { File(it, "app/src/main/assets/routes.json") }
                .plus(File("android/app/src/main/assets/routes.json"))
        val file = requireNotNull(candidates.firstOrNull(File::exists)) {
            "Could not locate bundled routes.json"
        }
        ManifestJson.decode(file.readText())
    }

    @Test
    fun `instagram links with hl parameter are query stripped then routed cleanly`() =
        runBlocking {
            val settings = AppSettings()
            // Strip-all is on by default: the share-tag query never reaches
            // the router, let alone the frontend instance.
            assertTrue(settings.queryParamStripEnabled)
            val pipeline = LinkPipeline(settings)
            val urls =
                listOf(
                    "https://www.instagram.com/reel/DTqr4mTElos/?hl=en",
                    "https://instagram.com/p/Da_2rc7CNeT/?hl=en",
                    "https://www.instagram.com/reel/Dc9VSQ2NpCp/?hl=en",
                )
            val router = UrlRouter(bundledManifest)

            urls.forEach { url ->
                val prepared = pipeline.prepare(url)
                assertEquals(url.substringBefore('?'), prepared.url)
                assertEquals(1, prepared.strippedQueryParams)
                val context = pipeline.routingContext(emptyMap(), nowMillis = 1_000L)
                val result = router.resolve(prepared.url, context)
                assertTrue("Expected Redirect for $url, got $result", result is RoutingResult.Redirect)
                val redirectedUrl = (result as RoutingResult.Redirect).redirectedUrl
                assertTrue("Expected no query in $redirectedUrl", !redirectedUrl.contains('?'))
            }
        }

    @Test
    fun `functional query params survive stripping`() =
        runBlocking {
            val pipeline = LinkPipeline(AppSettings())
            val prepared = pipeline.prepare("https://www.youtube.com/watch?v=abc&t=30s&hl=tr")
            assertEquals("https://www.youtube.com/watch?v=abc&t=30s", prepared.url)
            assertEquals(1, prepared.strippedQueryParams)
        }

    @Test
    fun `search fallback urls keep their query`() =
        runBlocking {
            val pipeline = LinkPipeline(AppSettings())
            val searchUrl = "https://search.example/?k=my+query"
            val prepared = pipeline.prepare(searchUrl, allowQueryParamStrip = false)
            assertEquals(searchUrl, prepared.url)
            assertEquals(0, prepared.strippedQueryParams)
        }

    @Test
    fun `disabling the strip setting keeps the query intact`() =
        runBlocking {
            val pipeline = LinkPipeline(AppSettings(queryParamStripEnabled = false))
            val url = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en"
            assertEquals(url, pipeline.prepare(url).url)
        }

    @Test
    fun `wrapped url is unwrapped first and only the target query is stripped`() =
        runBlocking {
            val pipeline = LinkPipeline(AppSettings())
            val wrapped =
                "https://www.google.com/url?q=https%3A%2F%2Fwww.instagram.com%2Freel%2FDTqr4mTElos%2F%3Fhl%3Den"
            val prepared = pipeline.prepare(wrapped)
            assertEquals("https://www.instagram.com/reel/DTqr4mTElos/", prepared.url)
            assertEquals(1, prepared.unwrappedLayers)
            assertEquals(1, prepared.strippedQueryParams)
        }

    @Test
    fun `user exceptions keep the url byte-for-byte including query`() =
        runBlocking {
            val url = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en"
            val pipeline =
                LinkPipeline(
                    AppSettings(exceptions = listOf(ExceptionRule.Domain("instagram.com"))),
                )
            assertEquals(url, pipeline.prepare(url).url)
            assertEquals(0, pipeline.prepare(url).strippedQueryParams)
        }

    @Test
    fun `instagram hl link with custom tracking rule and referrer exception behaves as configured`() =
        runBlocking {
            val url = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en"
            // Strip-all disabled here so the custom denylist rule is what
            // removes the parameter.
            val customRulePipeline =
                LinkPipeline(
                    AppSettings(
                        queryParamStripEnabled = false,
                        customTrackingParams = listOf(CustomTrackingParam(name = "hl", host = "instagram.com")),
                    ),
                )
            val prepared = customRulePipeline.prepare(url)
            val context = customRulePipeline.routingContext(emptyMap(), nowMillis = 1_000L)
            val result = UrlRouter(bundledManifest).resolve(prepared.url, context) as RoutingResult.Redirect
            assertTrue(result.redirectedUrl.endsWith("/reel/DTqr4mTElos/"))

            val exceptionPipeline =
                LinkPipeline(
                    AppSettings(
                        queryParamStripEnabled = false,
                        exceptions = listOf(ExceptionRule.SourceApp("com.instagram.android")),
                    ),
                    sourceApp = "com.instagram.android",
                )
            val excContext = exceptionPipeline.routingContext(emptyMap(), nowMillis = 1_000L)
            val excResult = UrlRouter(bundledManifest).resolve(url, excContext)
            assertEquals(RoutingResult.Passthrough(url), excResult)
        }

    @Test
    fun `ask mode routes still resolve to a redirect so the caller can show the ask dialog`() =
        runBlocking {
            val settings = AppSettings(askModeRoutes = setOf("instagram"))
            val pipeline = LinkPipeline(settings)
            val prepared = pipeline.prepare("https://www.instagram.com/reel/DTqr4mTElos/?hl=en")
            val context = pipeline.routingContext(emptyMap(), nowMillis = 1_000L)
            val result = UrlRouter(bundledManifest).resolve(prepared.url, context)
            assertTrue("Expected Redirect for ask-mode route, got $result", result is RoutingResult.Redirect)
            assertEquals("instagram", (result as RoutingResult.Redirect).routeId)
            assertTrue(settings.isRouteAskMode(result.routeId))
        }
}
