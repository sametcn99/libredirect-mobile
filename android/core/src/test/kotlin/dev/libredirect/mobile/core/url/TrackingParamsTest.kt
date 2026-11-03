package dev.libredirect.mobile.core.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingParamsTest {
    @Test
    fun `null and empty queries are left alone`() {
        assertNull(TrackingParams.cleanQuery(null, "example.org"))
        assertNull(TrackingParams.cleanQuery("", "example.org"))
    }

    @Test
    fun `query without tracking parameters is reported as unchanged`() {
        assertNull(TrackingParams.cleanQuery("v=abc&t=10", "youtube.com"))
    }

    @Test
    fun `utm parameters are removed by prefix rule`() {
        assertEquals("id=7", TrackingParams.cleanQuery("utm_source=x&utm_medium=social&id=7", "example.org"))
        assertEquals("id=7", TrackingParams.cleanQuery("id=7&utm_campaign=spring", "example.org"))
    }

    @Test
    fun `utm matching is case-insensitive`() {
        assertEquals("id=7", TrackingParams.cleanQuery("UTM_Source=x&id=7", "example.org"))
    }

    @Test
    fun `global click identifiers are removed`() {
        assertEquals("p=1", TrackingParams.cleanQuery("p=1&fbclid=IwAR123", "example.org"))
        assertEquals("p=1", TrackingParams.cleanQuery("p=1&gclid=EAIaIQ", "example.org"))
        assertEquals("p=1", TrackingParams.cleanQuery("igshid=abc&p=1", "example.org"))
    }

    @Test
    fun `youtube si parameter is stripped only on youtube hosts`() {
        assertEquals("", TrackingParams.cleanQuery("si=abc", "youtube.com"))
        assertEquals("v=1", TrackingParams.cleanQuery("v=1&si=abc", "www.youtube.com"))
        assertEquals("", TrackingParams.cleanQuery("si=abc", "youtu.be"))
        assertEquals("", TrackingParams.cleanQuery("si=abc", "m.youtube.com"))
        assertNull(TrackingParams.cleanQuery("si=abc", "example.org"))
    }

    @Test
    fun `kept pairs keep their original percent-encoding and order`() {
        assertEquals("q=a%20b&x=1", TrackingParams.cleanQuery("q=a%20b&utm_term=c&x=1", "example.org"))
    }

    @Test
    fun `undecodable pair names are kept`() {
        assertNull(TrackingParams.cleanQuery("a%ZZ=1", "example.org"))
    }

    @Test
    fun `empty pairs are preserved`() {
        assertEquals("&", TrackingParams.cleanQuery("&&utm_source=x", "example.org"))
    }

    @Test
    fun `flag-style pairs without value are matched by name`() {
        assertEquals("", TrackingParams.cleanQuery("utm_source", "example.org"))
    }

    @Test
    fun `value containing equals sign is not confused with the name`() {
        assertEquals("a=b=c", TrackingParams.cleanQuery("a=b=c&utm_source=x", "example.org"))
    }

    @Test
    fun `stripFrom rewrites only the query segment of the original string`() {
        val input = "https://user:pw@example.org:8443/p/a?t=1&utm_source=x&keep=1#frag?not=query"
        val url = UrlParser.parse(input)!!
        assertEquals(
            "https://user:pw@example.org:8443/p/a?t=1&keep=1#frag?not=query",
            TrackingParams.stripFrom(input, url),
        )
    }

    @Test
    fun `stripFrom drops the question mark when every pair is removed`() {
        val input = "https://example.org/?utm_source=x"
        val url = UrlParser.parse(input)!!
        assertEquals("https://example.org/", TrackingParams.stripFrom(input, url))
        val noPath = UrlParser.parse("https://example.org?fbclid=1")!!
        assertEquals("https://example.org", TrackingParams.stripFrom("https://example.org?fbclid=1", noPath))
    }

    @Test
    fun `stripFrom returns input unchanged when nothing matches`() {
        val input = "https://example.org/page?a=1"
        val url = UrlParser.parse(input)!!
        assertEquals(input, TrackingParams.stripFrom(input, url))
    }

    @Test
    fun `stripped ParsedUrl nulls an emptied query and keeps a cleaned one`() {
        val emptied = TrackingParams.stripped(UrlParser.parse("https://example.org/?utm_source=x")!!)
        assertNull(emptied.rawQuery)
        val cleaned = TrackingParams.stripped(UrlParser.parse("https://example.org/?id=1&utm_source=x")!!)
        assertEquals("id=1", cleaned.rawQuery)
        val untouched = TrackingParams.stripped(UrlParser.parse("https://example.org/?id=1")!!)
        assertEquals("id=1", untouched.rawQuery)
    }
}
