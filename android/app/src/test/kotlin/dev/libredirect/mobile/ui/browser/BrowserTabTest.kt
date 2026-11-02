package dev.libredirect.mobile.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabTest {
    @Test
    fun `default tab has expected defaults`() {
        val tab = BrowserTab()
        assertEquals("https://duckduckgo.com", tab.url)
        assertEquals("Yeni Sekme", tab.title)
        assertFalse(tab.isLoading)
        assertEquals(0, tab.progress)
        assertFalse(tab.canGoBack)
        assertFalse(tab.canGoForward)
        assertNull(tab.lastRedirect)
        assertNotNull(tab.id)
        assertFalse(tab.isDesktopMode)
        assertNull(tab.favicon)
        assertEquals("duckduckgo.com", tab.displayHost)
        assertTrue(tab.isSecure)
    }

    @Test
    fun `displayHost and security parse schemes correctly`() {
        val secureTab = BrowserTab(url = "https://www.wikipedia.org/wiki/Kotlin")
        assertEquals("wikipedia.org", secureTab.displayHost)
        assertTrue(secureTab.isSecure)

        val insecureTab = BrowserTab(url = "http://example.org/test")
        assertEquals("example.org", insecureTab.displayHost)
        assertFalse(insecureTab.isSecure)
    }

    @Test
    fun `page theme resolves correct dark mode preference`() {
        assertEquals(PageTheme.SYSTEM, PageTheme.fromId("system"))
        assertEquals(PageTheme.LIGHT, PageTheme.fromId("light"))
        assertEquals(PageTheme.DARK, PageTheme.fromId("dark"))
        assertEquals(PageTheme.SYSTEM, PageTheme.fromId("unknown"))

        assertTrue(PageTheme.DARK.isDark(systemInDarkTheme = false))
        assertFalse(PageTheme.LIGHT.isDark(systemInDarkTheme = true))
        assertTrue(PageTheme.SYSTEM.isDark(systemInDarkTheme = true))
        assertFalse(PageTheme.SYSTEM.isDark(systemInDarkTheme = false))
    }

    @Test
    fun `tab with redirect notice holds redirection metadata`() {
        val notice =
            RedirectNotice(
                originalUrl = "https://instagram.com/p/123",
                redirectedUrl = "https://proxigram.example.org/p/123",
                serviceName = "Instagram",
                frontendName = "Proxigram",
            )
        val tab = BrowserTab(url = notice.redirectedUrl, lastRedirect = notice)

        assertEquals("https://proxigram.example.org/p/123", tab.url)
        assertNotNull(tab.lastRedirect)
        assertEquals("Instagram", tab.lastRedirect?.serviceName)
        assertEquals("Proxigram", tab.lastRedirect?.frontendName)
        assertEquals("https://instagram.com/p/123", tab.lastRedirect?.originalUrl)
    }

    @Test
    fun `desktop mode toggles properly and defines desktop user agent`() {
        val tab = BrowserTab(isDesktopMode = true)
        assertTrue(tab.isDesktopMode)
        assertTrue(BrowserTab.DESKTOP_USER_AGENT.contains("Mozilla/5.0"))
        assertTrue(BrowserTab.DESKTOP_USER_AGENT.contains("Linux x86_64"))
    }
}
