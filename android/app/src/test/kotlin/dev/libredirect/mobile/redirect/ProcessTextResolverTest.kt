package dev.libredirect.mobile.redirect

import dev.libredirect.mobile.ui.browser.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessTextResolverTest {
    private val engine = SearchEngine.DUCKDUCKGO

    @Test
    fun `https url passes through untouched`() {
        val url = "https://www.youtube.com/watch?v=abc"
        assertEquals(url, ProcessTextResolver.resolve(url, engine))
    }

    @Test
    fun `http url passes through untouched`() {
        assertEquals("http://example.com/x", ProcessTextResolver.resolve("http://example.com/x", engine))
    }

    @Test
    fun `scheme check is case-insensitive`() {
        assertEquals("HTTPS://example.com", ProcessTextResolver.resolve("HTTPS://example.com", engine))
    }

    @Test
    fun `plain text becomes a search in the given engine`() {
        assertEquals(
            "https://duckduckgo.com/?q=hello+world",
            ProcessTextResolver.resolve("hello world", engine),
        )
    }

    @Test
    fun `a bare domain without scheme becomes a search, not a navigation`() {
        assertEquals(
            "https://duckduckgo.com/?q=example.com",
            ProcessTextResolver.resolve("example.com", engine),
        )
    }

    @Test
    fun `a custom search engine template is used`() {
        val custom =
            SearchEngine(
                id = "custom",
                displayName = "Custom",
                homeUrl = "https://s.example",
                queryTemplate = "https://s.example/find?q=%s",
            )
        assertEquals("https://s.example/find?q=cat", ProcessTextResolver.resolve("cat", custom))
    }
}
