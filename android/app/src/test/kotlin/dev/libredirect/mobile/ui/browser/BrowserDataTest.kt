package dev.libredirect.mobile.ui.browser

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `bookmark serialization and deserialization roundtrip`() {
        val bookmark =
            BrowserBookmark(
                id = "b1",
                title = "Example",
                url = "https://example.com",
                createdAtEpochMillis = 123456789L,
            )
        val serialized = json.encodeToString(listOf(bookmark))
        val deserialized = json.decodeFromString<List<BrowserBookmark>>(serialized)

        assertEquals(1, deserialized.size)
        assertEquals("b1", deserialized.first().id)
        assertEquals("Example", deserialized.first().title)
        assertEquals("https://example.com", deserialized.first().url)
        assertEquals(123456789L, deserialized.first().createdAtEpochMillis)
    }

    @Test
    fun `history item serialization and deserialization roundtrip`() {
        val historyItem =
            BrowserHistoryItem(
                id = "h1",
                title = "Wikipedia",
                url = "https://wikipedia.org",
                visitedAtEpochMillis = 987654321L,
            )
        val serialized = json.encodeToString(listOf(historyItem))
        val deserialized = json.decodeFromString<List<BrowserHistoryItem>>(serialized)

        assertEquals(1, deserialized.size)
        assertEquals("h1", deserialized.first().id)
        assertEquals("Wikipedia", deserialized.first().title)
        assertEquals("https://wikipedia.org", deserialized.first().url)
        assertEquals(987654321L, deserialized.first().visitedAtEpochMillis)
    }

    @Test
    fun `isCurrentPageBookmarked returns true when active tab matches bookmark`() {
        val activeTab = BrowserTab(id = "tab1", url = "https://news.ycombinator.com")
        val bookmark = BrowserBookmark(id = "b1", title = "Hacker News", url = "https://news.ycombinator.com")

        val state =
            BrowserUiState(
                tabs = listOf(activeTab),
                activeTabId = activeTab.id,
                bookmarks = listOf(bookmark),
            )

        assertTrue(state.isCurrentPageBookmarked)
    }

    @Test
    fun `isCurrentPageBookmarked returns false for default home url or empty`() {
        val homeTab = BrowserTab(id = "home", url = BrowserTab.DEFAULT_HOME_URL)
        val bookmark = BrowserBookmark(id = "b1", title = "Home", url = BrowserTab.DEFAULT_HOME_URL)

        val state =
            BrowserUiState(
                tabs = listOf(homeTab),
                activeTabId = homeTab.id,
                bookmarks = listOf(bookmark),
            )

        assertFalse(state.isCurrentPageBookmarked)
    }
}
