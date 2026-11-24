package dev.libredirect.mobile.ui.browser

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import dev.libredirect.mobile.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateTabProfileTest {
    @Test
    fun privateChildUsesPrivateProfileOrPrivateTabIsUnavailable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val browser = ViewModelProvider(activity)[BrowserViewModel::class.java]
                val normal = requireNotNull(browser.uiState.value.activeTab)
                browser.openIncognitoTab()
                if (!WebViewProfileManager.isSupported()) {
                    assertEquals(normal.id, browser.uiState.value.activeTab?.id)
                    assertTrue(browser.uiState.value.tabs.none { it.isIncognito })
                    return@onActivity
                }
                val parent = requireNotNull(browser.uiState.value.activeTab)
                browser.openChildTab("https://example.org/private-child", parent.id)
                val child = requireNotNull(browser.uiState.value.activeTab)
                assertTrue(parent.isIncognito)
                assertTrue(child.isIncognito)
                val parentName = WebViewCompat.getProfile(browser.getOrCreateWebView(parent.id, activity, false)).name
                val childName = WebViewCompat.getProfile(browser.getOrCreateWebView(child.id, activity, false)).name
                val normalName = WebViewCompat.getProfile(browser.getOrCreateWebView(normal.id, activity, false)).name
                assertEquals(parentName, childName)
                assertNotEquals(normalName, childName)
                browser.closeIncognitoTabs()
                assertTrue(browser.uiState.value.tabs.none { it.isIncognito })
                browser.openIncognitoTab()
                val fresh = browser.uiState.value.activeTab
                assertNotNull(fresh)
                val freshView = browser.getOrCreateWebView(requireNotNull(fresh).id, activity, false)
                assertNotEquals(childName, WebViewCompat.getProfile(freshView).name)
            }
        }
    }
}
