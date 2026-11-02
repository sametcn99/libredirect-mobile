package dev.libredirect.mobile.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkCodecTest {
    @Test
    fun `exportToHtml produces standard Netscape Bookmark header and items`() {
        val bookmarks =
            listOf(
                BrowserBookmark(
                    id = "b1",
                    title = "LibRedirect & Tools",
                    url = "https://libredirect.github.io?query=1&test=true",
                    createdAtEpochMillis = 1700000000000L,
                ),
            )

        val html = BookmarkCodec.exportToHtml(bookmarks)
        assertTrue(html.contains("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        assertTrue(html.contains("<TITLE>Bookmarks</TITLE>"))
        assertTrue(html.contains("HREF=\"https://libredirect.github.io?query=1&amp;test=true\""))
        assertTrue(html.contains("LibRedirect &amp; Tools</A>"))
    }

    @Test
    fun `exportToJson produces valid JSON array`() {
        val bookmarks =
            listOf(
                BrowserBookmark(
                    id = "b1",
                    title = "DuckDuckGo",
                    url = "https://duckduckgo.com",
                ),
            )

        val json = BookmarkCodec.exportToJson(bookmarks)
        assertTrue(json.contains("\"title\": \"DuckDuckGo\""))
        assertTrue(json.contains("\"url\": \"https://duckduckgo.com\""))
    }

    @Test
    fun `parseBookmarks successfully parses Netscape HTML format`() {
        val html =
            """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <TITLE>Bookmarks</TITLE>
            <H1>Bookmarks</H1>
            <DL><p>
                <DT><A HREF="https://duckduckgo.com" ADD_DATE="1700000000">DuckDuckGo &amp; Privacy</A>
                <DT><A HREF="https://wikipedia.org">Wikipedia</A>
            </DL><p>
            """.trimIndent()

        val parsed = BookmarkCodec.parseBookmarks(html)
        assertEquals(2, parsed.size)
        assertEquals("https://duckduckgo.com", parsed[0].url)
        assertEquals("DuckDuckGo & Privacy", parsed[0].title)
        assertEquals("https://wikipedia.org", parsed[1].url)
        assertEquals("Wikipedia", parsed[1].title)
    }

    @Test
    fun `parseBookmarks successfully parses nested HTML with inner tags and attributes`() {
        val html =
            """
            <DL><p>
                <DT><H3 ADD_DATE="1600000000">Privacy Tools</H3>
                <DL><p>
                    <DT><A HREF='https://teddit.net' ICON="data:..." LAST_MODIFIED="1600000001"><span>Teddit</span></A>
                    <DT><A HREF="https://invidious.io">Invidious</A>
                </DL><p>
            </DL><p>
            """.trimIndent()

        val parsed = BookmarkCodec.parseBookmarks(html)
        assertEquals(2, parsed.size)
        assertEquals("https://teddit.net", parsed[0].url)
        assertEquals("Teddit", parsed[0].title)
        assertEquals("https://invidious.io", parsed[1].url)
        assertEquals("Invidious", parsed[1].title)
    }

    @Test
    fun `parseBookmarks successfully parses JSON array format`() {
        val json =
            """
            [
                {"title": "Search", "url": "https://searx.be"},
                {"id": "b2", "title": "GitHub", "url": "https://github.com", "createdAtEpochMillis": 123456}
            ]
            """.trimIndent()

        val parsed = BookmarkCodec.parseBookmarks(json)
        assertEquals(2, parsed.size)
        assertEquals("https://searx.be", parsed[0].url)
        assertEquals("Search", parsed[0].title)
        assertEquals("https://github.com", parsed[1].url)
        assertEquals("GitHub", parsed[1].title)
    }

    @Test
    fun `parseBookmarks successfully parses wrapped JSON format`() {
        val wrappedJson =
            """
            {
                "bookmarks": [
                    {"title": "Reddit", "url": "https://reddit.com"}
                ]
            }
            """.trimIndent()

        val parsed = BookmarkCodec.parseBookmarks(wrappedJson)
        assertEquals(1, parsed.size)
        assertEquals("https://reddit.com", parsed[0].url)
        assertEquals("Reddit", parsed[0].title)
    }

    @Test
    fun `parseBookmarks ignores invalid, empty or javascript URLs`() {
        val html =
            """
            <DT><A HREF="javascript:alert(1)">Exploit</A>
            <DT><A HREF="about:blank">Blank</A>
            <DT><A HREF="">Empty URL</A>
            <DT><A HREF="https://valid.com"></A>
            """.trimIndent()

        val parsed = BookmarkCodec.parseBookmarks(html)
        assertEquals(1, parsed.size)
        assertEquals("https://valid.com", parsed[0].url)
        assertEquals("https://valid.com", parsed[0].title)
    }

    @Test
    fun `parseBookmarks returns empty list on invalid input`() {
        val parsed = BookmarkCodec.parseBookmarks("not a valid bookmark file")
        assertTrue(parsed.isEmpty())

        val empty = BookmarkCodec.parseBookmarks("")
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `export and parse roundtrip HTML preserves bookmarks`() {
        val original =
            listOf(
                BrowserBookmark(id = "1", title = "Site 1", url = "https://site1.org"),
                BrowserBookmark(id = "2", title = "Site 2 & More", url = "https://site2.org?a=1&b=2"),
            )

        val exportedHtml = BookmarkCodec.exportToHtml(original)
        val imported = BookmarkCodec.parseBookmarks(exportedHtml)

        assertEquals(2, imported.size)
        assertEquals("https://site1.org", imported[0].url)
        assertEquals("Site 1", imported[0].title)
        assertEquals("https://site2.org?a=1&b=2", imported[1].url)
        assertEquals("Site 2 & More", imported[1].title)
    }

    @Test
    fun `export and parse roundtrip JSON preserves bookmarks`() {
        val original =
            listOf(
                BrowserBookmark(id = "1", title = "Site 1", url = "https://site1.org"),
                BrowserBookmark(id = "2", title = "Site 2", url = "https://site2.org"),
            )

        val exportedJson = BookmarkCodec.exportToJson(original)
        val imported = BookmarkCodec.parseBookmarks(exportedJson)

        assertEquals(2, imported.size)
        assertEquals("https://site1.org", imported[0].url)
        assertEquals("Site 1", imported[0].title)
        assertEquals("https://site2.org", imported[1].url)
        assertEquals("Site 2", imported[1].title)
    }
}
