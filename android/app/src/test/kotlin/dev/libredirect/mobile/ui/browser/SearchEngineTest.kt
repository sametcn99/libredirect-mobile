package dev.libredirect.mobile.ui.browser

import org.junit.Assert.assertEquals
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
}
