package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.ManifestJson
import dev.libredirect.mobile.core.url.CustomTrackingScope
import dev.libredirect.mobile.core.url.TrackingParams
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.core.url.UrlUnwrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstagramLinkRegressionTest {
    private val bundledManifest by lazy {
        val userDir = System.getProperty("user.dir").orEmpty()
        val candidates =
            generateSequence(File(userDir)) { it.parentFile }
                .map { File(it, "app/src/main/assets/routes.json") }
                .plus(File("android/app/src/main/assets/routes.json"))
        val file =
            requireNotNull(candidates.firstOrNull(File::exists)) {
                "Could not locate bundled routes.json"
            }
        ManifestJson.decode(file.readText())
    }

    @Test
    fun `issue 12 instagram links with hl parameter route cleanly via bundled manifest`() {
        val router = UrlRouter(bundledManifest)
        val links =
            listOf(
                "https://www.instagram.com/reel/DTqr4mTElos/?hl=en",
                "https://instagram.com/p/Da_2rc7CNeT/?hl=en",
                "https://www.instagram.com/reel/Dc9VSQ2NpCp/?hl=en",
            )
        val route = bundledManifest.routes.find { it.id == "instagram" }
        assertNotNull("Expected instagram route in bundled manifest", route)

        links.forEach { link ->
            val result = router.resolve(link, RoutingContext())
            assertTrue("Expected redirect for $link, got $result", result is RoutingResult.Redirect)
            val redirect = result as RoutingResult.Redirect
            assertEquals("instagram", redirect.routeId)
            assertTrue(
                "Expected query parameter preserved in ${redirect.redirectedUrl}",
                redirect.redirectedUrl.endsWith("?hl=en"),
            )
            assertNotNull("Expected instance for $link", redirect.instance)
        }
    }

    @Test
    fun `instagram links with hl and tracking params strip only tracking params`() {
        val router = UrlRouter(bundledManifest)
        val link = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en&igsh=abc123&utm_source=ig_web_copy_link"
        val result = router.resolve(link, RoutingContext())
        assertTrue(result is RoutingResult.Redirect)
        val redirect = result as RoutingResult.Redirect
        assertTrue(
            "Expected ?hl=en kept and tracking params removed: ${redirect.redirectedUrl}",
            redirect.redirectedUrl.endsWith("/reel/DTqr4mTElos/?hl=en"),
        )
    }

    @Test
    fun `custom tracking rule stripping hl keeps valid target path without crashing`() {
        val router = UrlRouter(bundledManifest)
        val link = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en"
        val context = RoutingContext(customTrackingRules = CustomTrackingRules(global = setOf("hl")))
        val result = router.resolve(link, context)
        assertTrue(result is RoutingResult.Redirect)
        val redirect = result as RoutingResult.Redirect
        assertTrue(
            "Expected ?hl=en stripped cleanly: ${redirect.redirectedUrl}",
            redirect.redirectedUrl.endsWith("/reel/DTqr4mTElos/"),
        )

        val parsed = UrlParser.parse(link)!!
        assertEquals(1, TrackingParams.removedCount(parsed.rawQuery, parsed.host, CustomTrackingScope(setOf("hl"))))
    }

    @Test
    fun `shared and wrapped instagram hl links unwrap and extract cleanly`() {
        val wrapped =
            "https://www.google.com/url?q=https%3A%2F%2Fwww.instagram.com%2Freel%2FDTqr4mTElos%2F%3Fhl%3Den"
        val extracted = UrlCandidate.find("Check out $wrapped")
        assertEquals(wrapped, extracted)
        val unwrapped = UrlUnwrapper.unwrapAll(extracted!!)
        assertEquals("https://www.instagram.com/reel/DTqr4mTElos/?hl=en", unwrapped.url)

        val result = UrlRouter(bundledManifest).resolve(unwrapped.url, RoutingContext())
        assertTrue(result is RoutingResult.Redirect)
    }
}
