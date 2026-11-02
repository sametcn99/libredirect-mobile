package dev.libredirect.mobile.ui.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private const val MILLIS_PER_SECOND = 1000L

object BookmarkCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

    private val htmlLinkRegex =
        Regex(
            """<a\b[^>]*?\bhref=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    private val htmlTagRegex = Regex("""<[^>]*>""")

    @Serializable
    private data class BookmarkJsonWrapper(
        val bookmarks: List<BrowserBookmark> = emptyList(),
    )

    fun exportToHtml(bookmarks: List<BrowserBookmark>): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        sb.appendLine("<!-- This is an automatically generated file.")
        sb.appendLine("     It will be read and overwritten.")
        sb.appendLine("     DO NOT EDIT! -->")
        sb.appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        sb.appendLine("<TITLE>Bookmarks</TITLE>")
        sb.appendLine("<H1>Bookmarks</H1>")
        sb.appendLine("<DL><p>")
        for (b in bookmarks) {
            val addDate = b.createdAtEpochMillis / MILLIS_PER_SECOND
            val escapedUrl = escapeHtml(b.url)
            val escapedTitle = escapeHtml(b.title.ifBlank { b.url })
            sb.appendLine("    <DT><A HREF=\"$escapedUrl\" ADD_DATE=\"$addDate\">$escapedTitle</A>")
        }
        sb.appendLine("</DL><p>")
        return sb.toString()
    }

    fun exportToJson(bookmarks: List<BrowserBookmark>): String = json.encodeToString(bookmarks)

    fun parseBookmarks(raw: String): List<BrowserBookmark> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        return if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            parseJson(trimmed).ifEmpty { parseHtml(trimmed) }
        } else {
            parseHtml(trimmed).ifEmpty { parseJson(trimmed) }
        }
    }

    private fun parseJson(content: String): List<BrowserBookmark> {
        return try {
            json.decodeFromString<List<BrowserBookmark>>(content)
        } catch (_: SerializationException) {
            try {
                json.decodeFromString<BookmarkJsonWrapper>(content).bookmarks
            } catch (_: Exception) {
                emptyList()
            }
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun parseHtml(content: String): List<BrowserBookmark> {
        val results = mutableListOf<BrowserBookmark>()
        for (match in htmlLinkRegex.findAll(content)) {
            val url = unescapeHtml(match.groupValues[1].trim())
            if (shouldSkipUrl(url)) continue

            val rawTitle = match.groupValues[2].replace(htmlTagRegex, "").trim()
            val title = unescapeHtml(rawTitle).ifBlank { url }
            results.add(
                BrowserBookmark(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    url = url,
                ),
            )
        }
        return results
    }

    private fun shouldSkipUrl(url: String): Boolean =
        url.isBlank() ||
            url.startsWith("javascript:", ignoreCase = true) ||
            url.equals("about:blank", ignoreCase = true)

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun unescapeHtml(text: String): String =
        text.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
