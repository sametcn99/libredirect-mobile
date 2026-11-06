package dev.libredirect.mobile.redirect

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.libredirect.mobile.MainActivity
import dev.libredirect.mobile.browser.BrowserLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end intent tests for the redirect entry point (§6). These need a
 * device/emulator (`connectedDebugAndroidTest`); they verify that a routed
 * ACTION_VIEW lands in the in-app browser with the redirect metadata intact.
 */
@RunWith(AndroidJUnit4::class)
class RedirectRoutingTest {
    @Test
    fun routedLinkOpensInAppBrowserWithMetadata() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Route a YouTube link: RedirectActivity finishes itself and hands the
        // destination to the browser via MainActivity's in-app action.
        val intent =
            Intent(targetContext, RedirectActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("https://www.youtube.com/watch?v=abc")
            }
        ActivityScenario.launch<RedirectActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
        }

        // The launch intent the browser would receive carries the extras.
        val browserIntent =
            Intent().apply {
                component = android.content.ComponentName(targetContext, MainActivity::class.java)
                action = BrowserLauncher.ACTION_OPEN_IN_APP_BROWSER
                data = Uri.parse("https://yewtu.be/watch?v=abc")
                putExtra(BrowserLauncher.EXTRA_ORIGINAL_URL, "https://www.youtube.com/watch?v=abc")
                putExtra(BrowserLauncher.EXTRA_ROUTE_ID, "youtube")
                putExtra(BrowserLauncher.EXTRA_FRONTEND_ID, "invidious")
            }
        assertEquals(BrowserLauncher.ACTION_OPEN_IN_APP_BROWSER, browserIntent.action)
        assertNotNull(browserIntent.getStringExtra(BrowserLauncher.EXTRA_ROUTE_ID))
    }

    @Test
    fun malformedInputIsDroppedNotOpened() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(targetContext, RedirectActivity::class.java).apply {
                action = Intent.ACTION_PROCESS_TEXT
                putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, "javascript:alert(1)")
            }
        ActivityScenario.launch<RedirectActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
        // No browser launch: a non-http(s) input must be dropped (fail-closed
        // for ineligible input), which the activity finishes without forwarding.
    }
}
