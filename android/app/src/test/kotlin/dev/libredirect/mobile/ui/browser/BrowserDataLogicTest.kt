package dev.libredirect.mobile.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDataLogicTest {
    private val history =
        listOf(
            BrowserHistoryItem(
                id = "h1",
                title = "Wikipedia",
                url = "https://wikipedia.org",
                visitedAtEpochMillis = 1L,
            ),
            BrowserHistoryItem(
                id = "h2",
                title = "Piped Video",
                url = "https://piped.video/watch?v=1",
                visitedAtEpochMillis = 2L,
            ),
            BrowserHistoryItem(
                id = "h3",
                title = "Hacker News",
                url = "https://news.ycombinator.com",
                visitedAtEpochMillis = 3L,
            ),
        )

    @Test
    fun `blank query returns the whole history`() {
        assertEquals(history, filterBrowserHistory(history, ""))
        assertEquals(history, filterBrowserHistory(history, "   "))
    }

    @Test
    fun `matches titles case-insensitively`() {
        assertEquals(listOf("h1"), filterBrowserHistory(history, "wiki").map { it.id })
        assertEquals(listOf("h3"), filterBrowserHistory(history, "HACKER").map { it.id })
    }

    @Test
    fun `matches urls case-insensitively`() {
        assertEquals(listOf("h2"), filterBrowserHistory(history, "piped.video/watch").map { it.id })
        assertEquals(listOf("h3"), filterBrowserHistory(history, "YCOMBINATOR").map { it.id })
    }

    @Test
    fun `no match returns an empty list`() {
        assertTrue(filterBrowserHistory(history, "does-not-exist").isEmpty())
    }

    @Test
    fun `latency filter keeps only instances the picker shows`() {
        val cached = mapOf("https://a.example" to 10L, "https://b.example" to 20L, "https://gone.example" to 30L)
        assertEquals(
            mapOf("https://a.example" to 10L, "https://b.example" to 20L),
            filterLatencies(cached, listOf("https://a.example", "https://b.example")),
        )
    }

    @Test
    fun `latency filter of an empty cache stays empty`() {
        assertTrue(filterLatencies(emptyMap(), listOf("https://a.example")).isEmpty())
    }
}
