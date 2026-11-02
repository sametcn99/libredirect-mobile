package dev.libredirect.mobile.ui.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class SearchEngine(
    val id: String,
    val displayName: String,
    val homeUrl: String,
    val queryTemplate: String,
) {
    DUCKDUCKGO(
        id = "duckduckgo",
        displayName = "DuckDuckGo",
        homeUrl = "https://duckduckgo.com",
        queryTemplate = "https://duckduckgo.com/?q=%s",
    ),
    SEARXNG(
        id = "searxng",
        displayName = "SearXNG",
        homeUrl = "https://searx.be",
        queryTemplate = "https://searx.be/search?q=%s",
    ),
    BRAVE(
        id = "brave",
        displayName = "Brave Search",
        homeUrl = "https://search.brave.com",
        queryTemplate = "https://search.brave.com/search?q=%s",
    ),
    GOOGLE(
        id = "google",
        displayName = "Google",
        homeUrl = "https://www.google.com",
        queryTemplate = "https://www.google.com/search?q=%s",
    ),
    STARTPAGE(
        id = "startpage",
        displayName = "Startpage",
        homeUrl = "https://www.startpage.com",
        queryTemplate = "https://www.startpage.com/sp/search?query=%s",
    ),
    ;

    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return queryTemplate.replace("%s", encoded)
    }

    companion object {
        val DEFAULT = DUCKDUCKGO

        fun fromId(id: String?): SearchEngine = entries.find { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
