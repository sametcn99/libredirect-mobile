package dev.libredirect.mobile.redirect

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.libredirect.mobile.core.manifest.ManifestJson
import dev.libredirect.mobile.core.routing.RoutingContext
import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.routing.UrlRouter
import dev.libredirect.mobile.core.url.QueryParams
import dev.libredirect.mobile.core.url.QueryString
import dev.libredirect.mobile.core.url.TrackingParams
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.core.url.UrlUnwrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #12 regression coverage on a REAL Android runtime
 * (`connectedDebugAndroidTest`). JVM unit tests cannot catch this bug class:
 * the desktop JDK ships the Java 10 `URLDecoder.decode(String, Charset)`
 * overload, so the old code passed every JVM test while throwing
 * `NoSuchMethodError` on devices below API 33. Run this suite against an
 * API 26-32 emulator (e.g. the libredirect-api28 AVD) to prove the routing
 * pipeline works on the minimum supported runtime.
 */
@RunWith(AndroidJUnit4::class)
class Issue12ApiLevelRegressionTest {
    private val instagramHlLink = "https://www.instagram.com/reel/DTqr4mTElos/?hl=en"

    @Test
    fun queryParamStrippingWorksOnDeviceRuntime() {
        val parsed = UrlParser.parse(instagramHlLink)!!
        val stripped = QueryParams.stripShareTags(instagramHlLink, parsed)
        assertEquals("https://www.instagram.com/reel/DTqr4mTElos/", stripped.url)
        assertEquals(1, stripped.removedCount)
    }

    @Test
    fun trackingParamDecodingWorksOnDeviceRuntime() {
        // The percent-encoded name exercises URLDecoder through the legacy
        // String overload — the exact call that used to throw NoSuchMethodError.
        assertEquals("id=7", TrackingParams.cleanQuery("utm%5Fsource=x&id=7", "example.org"))
    }

    @Test
    fun wrapperUnwrappingWorksOnDeviceRuntime() {
        assertEquals(
            "https://www.instagram.com/reel/DTqr4mTElos/?hl=en",
            UrlUnwrapper.unwrapOnce(
                "https://www.google.com/url?q=https%3A%2F%2Fwww.instagram.com%2Freel%2FDTqr4mTElos%2F%3Fhl%3Den",
            ),
        )
    }

    @Test
    fun queryStringFindAndEncodeRoundTripOnDeviceRuntime() {
        val encoded = QueryString.encode("çift güven")
        assertEquals("çift güven", QueryString.find("k=$encoded", "k"))
    }

    @Test
    fun bundledManifestRedirectsInstagramHlLinkOnDeviceRuntime() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest =
            targetContext.assets.open("routes.json").bufferedReader().use { reader ->
                ManifestJson.decode(reader.readText())
            }
        val result = UrlRouter(manifest).resolve(instagramHlLink, RoutingContext())
        assertTrue("Expected a redirect, got $result", result is RoutingResult.Redirect)
        assertEquals("instagram", (result as RoutingResult.Redirect).routeId)
    }

    @Test
    fun redirectActivityHandlesInstagramHlLinkWithoutCrashing() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(targetContext, RedirectActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(instagramHlLink)
            }
        // RedirectActivity finishes itself right after the handoff; waiting
        // for that finish (instead of onActivity, which races with it — see
        // RedirectRoutingTest) proves the full route ran without crashing.
        ActivityScenario.launch<RedirectActivity>(intent).use { scenario ->
            scenario.awaitDestroyed()
        }
    }
}
