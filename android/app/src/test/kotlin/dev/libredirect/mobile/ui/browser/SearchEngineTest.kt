package dev.libredirect.mobile.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    @Test
    fun `default engine is DuckDuckGo`() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.DEFAULT)
        assertEquals("duckduckgo", SearchEngine.DEFAULT.id)
    }

    @Test
    fun `fromId resolves known engines`() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromId("duckduckgo"))
        assertEquals(SearchEngine.SEARXNG, SearchEngine.fromId("searxng"))
        assertEquals(SearchEngine.BRAVE, SearchEngine.fromId("brave"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId("google"))
        assertEquals(SearchEngine.STARTPAGE, SearchEngine.fromId("startpage"))
    }

    @Test
    fun `fromId falls back to default on unknown or null`() {
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromId(null))
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromId(""))
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromId("unknown_engine"))
    }

    @Test
    fun `buildSearchUrl properly encodes query parameters`() {
        val ddgUrl = SearchEngine.DUCKDUCKGO.buildSearchUrl("privacy browser")
        assertEquals("https://duckduckgo.com/?q=privacy+browser", ddgUrl)

        val searxngUrl = SearchEngine.SEARXNG.buildSearchUrl("kotlin compose")
        assertEquals("https://searx.be/search?q=kotlin+compose", searxngUrl)

        val braveUrl = SearchEngine.BRAVE.buildSearchUrl("libredirect")
        assertEquals("https://search.brave.com/search?q=libredirect", braveUrl)

        val specialCharsUrl = SearchEngine.GOOGLE.buildSearchUrl("c++ & java #1")
        assertTrue(specialCharsUrl.startsWith("https://www.google.com/search?q="))
        assertTrue(specialCharsUrl.contains("%26"))
    }

    @Test
    fun `isValidTemplate accepts only http(s) templates containing %s placeholder`() {
        assertTrue(SearchEngine.isValidTemplate("https://example.com/search?q=%s"))
        assertTrue(SearchEngine.isValidTemplate("http://example.com/?query=%s"))
        assertTrue(SearchEngine.isValidTemplate("HTTPS://EXAMPLE.COM/search?q=%s"))

        assertFalse(SearchEngine.isValidTemplate("example.com/search?q=%s"))
        assertFalse(SearchEngine.isValidTemplate("ftp://example.com/search?q=%s"))
        assertFalse(SearchEngine.isValidTemplate("https://example.com/search?q="))
        assertFalse(SearchEngine.isValidTemplate(""))
    }

    @Test
    fun `customOrNull builds engine with derived home url`() {
        val custom = SearchEngine.customOrNull("My Engine", "https://search.example.com/find?q=%s")

        assertTrue(custom != null)
        assertEquals(SearchEngine.CUSTOM_ID, custom?.id)
        assertEquals("My Engine", custom?.displayName)
        assertEquals("https://search.example.com/find", custom?.homeUrl)
        assertEquals("https://search.example.com/find?q=kotlin", custom?.buildSearchUrl("kotlin"))
    }

    @Test
    fun `customOrNull falls back to host name when name is blank`() {
        val custom = SearchEngine.customOrNull("", "https://search.example.com/find?q=%s")

        assertEquals("search.example.com", custom?.displayName)
    }

    @Test
    fun `customOrNull returns null on invalid template`() {
        assertEquals(null, SearchEngine.customOrNull("My Engine", "not a template"))
        assertEquals(null, SearchEngine.customOrNull("My Engine", ""))
    }

    @Test
    fun `resolve returns custom engine when selected`() {
        val resolved =
            SearchEngine.resolve(
                selectedId = "custom",
                customName = "My Engine",
                customUrl = "https://search.example.com/find?q=%s",
            )

        assertEquals(SearchEngine.CUSTOM_ID, resolved.id)
        assertEquals("My Engine", resolved.displayName)
    }

    @Test
    fun `resolve falls back to built-in when custom is missing or invalid`() {
        assertEquals(
            SearchEngine.DEFAULT,
            SearchEngine.resolve("custom", "My Engine", ""),
        )
        assertEquals(
            SearchEngine.DEFAULT,
            SearchEngine.resolve("custom", "", "invalid template"),
        )
        assertEquals(
            SearchEngine.BRAVE,
            SearchEngine.resolve("brave", "My Engine", "https://search.example.com/find?q=%s"),
        )
    }
}
