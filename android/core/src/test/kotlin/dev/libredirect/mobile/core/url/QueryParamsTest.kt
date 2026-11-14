package dev.libredirect.mobile.core.url

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryParamsTest {
    private fun strip(url: String): QueryParams.StripResult {
        val parsed = UrlParser.parse(url)!!
        return QueryParams.stripAllExcept(url, parsed)
    }

    @Test
    fun `non functional params are removed and counted`() {
        val result = strip("https://www.instagram.com/reel/DTqr4mTElos/?hl=en&igsh=abc")
        assertEquals("https://www.instagram.com/reel/DTqr4mTElos/", result.url)
        assertEquals(2, result.removedCount)
    }

    @Test
    fun `functional params survive`() {
        val result = strip("https://www.youtube.com/watch?v=abc&t=30s&list=PL1&hl=tr&si=x")
        assertEquals("https://www.youtube.com/watch?v=abc&t=30s&list=PL1", result.url)
        assertEquals(2, result.removedCount)
    }

    @Test
    fun `search queries survive`() {
        val result = strip("https://duckduckgo.com/?q=kitten+videos&ia=web")
        assertEquals("https://duckduckgo.com/?q=kitten+videos", result.url)
    }

    @Test
    fun `fragment and everything before the query stay byte-for-byte`() {
        val result = strip("https://example.org:8443/a/path/?x=1#section")
        assertEquals("https://example.org:8443/a/path/#section", result.url)
    }

    @Test
    fun `url without query is returned unchanged`() {
        val url = "https://www.instagram.com/reel/DTqr4mTElos/"
        val result = strip(url)
        assertEquals(url, result.url)
        assertEquals(0, result.removedCount)
    }

    @Test
    fun `query with only functional pairs is returned unchanged`() {
        val url = "https://news.ycombinator.com/item?id=12345"
        val result = strip(url)
        assertEquals(url, result.url)
        assertEquals(0, result.removedCount)
    }

    @Test
    fun `custom keep set replaces the default allowlist`() {
        val parsed = UrlParser.parse("https://example.com/?keep=1&drop=2")!!
        val result = QueryParams.stripAllExcept("https://example.com/?keep=1&drop=2", parsed, keep = setOf("keep"))
        assertEquals("https://example.com/?keep=1", result.url)
        assertEquals(1, result.removedCount)
    }
}
