package dev.libredirect.mobile.core.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlCandidateTest {
    @Test
    fun `null blank and empty text yield nothing`() {
        assertNull(UrlCandidate.find(null))
        assertNull(UrlCandidate.find(""))
        assertNull(UrlCandidate.find("   "))
    }

    @Test
    fun `first http url in prose wins`() {
        assertEquals(
            "https://example.org/page",
            UrlCandidate.find("check this out https://example.org/page and also https://other.example/x"),
        )
    }

    @Test
    fun `bare domain gets an https scheme`() {
        assertEquals("https://youtube.com/watch?v=abc", UrlCandidate.find("look: youtube.com/watch?v=abc"))
    }

    @Test
    fun `http scheme is preserved`() {
        assertEquals("http://example.net/a", UrlCandidate.find("http://example.net/a"))
    }

    @Test
    fun `text without any dotted host yields nothing`() {
        assertNull(UrlCandidate.find("no links here, just words"))
    }

    @Test
    fun `numbers and times are not treated as hosts`() {
        assertNull(UrlCandidate.find("at 12:30 version 1.2.3 was released"))
    }

    @Test
    fun `punctuation at the end is not part of the url`() {
        assertEquals("https://example.com/page", UrlCandidate.find("(see https://example.com/page.)"))
    }

    @Test
    fun `localhost without dots is ignored but real hosts are found`() {
        assertEquals("https://example.org/", UrlCandidate.find("open localhost or https://example.org/"))
    }
}
