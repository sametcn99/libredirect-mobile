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
    fun `instagram links with hl parameter route cleanly through LinkPipeline`() =
        runBlocking {
            val settings = AppSettings()
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
                assertEquals(url, prepared.url)
                val context = pipeline.routingContext(emptyMap(), nowMillis = 1_000L)
                val result = router.resolve(prepared.url, context)
                assertTrue("Expected Redirect for $url, got $result", result is RoutingResult.Redirect)
                val redirectedUrl = (result as RoutingResult.Redirect).redirectedUrl
                assertTrue("Expected ?hl=en preserved in $redirectedUrl", redirectedUrl.endsWith("?hl=en"))
            }
        }

    @Test
    fun `instagram hl link with custom tracking rule and referrer exception behaves as configured`() =
        runBlocking {
            val url = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en"
            val customRulePipeline =
                LinkPipeline(
                    AppSettings(
                        customTrackingParams = listOf(CustomTrackingParam(name = "hl", host = "instagram.com")),
                    ),
                )
            val prepared = customRulePipeline.prepare(url)
            val context = customRulePipeline.routingContext(emptyMap(), nowMillis = 1_000L)
            val result = UrlRouter(bundledManifest).resolve(prepared.url, context) as RoutingResult.Redirect
            assertTrue(result.redirectedUrl.endsWith("/reel/DTqr4mTElos/"))

            val exceptionPipeline =
                LinkPipeline(
                    AppSettings(exceptions = listOf(ExceptionRule.SourceApp("com.instagram.android"))),
                    sourceApp = "com.instagram.android",
                )
            val excContext = exceptionPipeline.routingContext(emptyMap(), nowMillis = 1_000L)
            val excResult = UrlRouter(bundledManifest).resolve(url, excContext)
            assertEquals(RoutingResult.Passthrough(url), excResult)
        }
}
