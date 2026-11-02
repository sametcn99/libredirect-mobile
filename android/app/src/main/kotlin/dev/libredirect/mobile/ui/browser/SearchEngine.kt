package dev.libredirect.mobile.ui.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SearchEngine(
    val id: String,
    val displayName: String,
    val homeUrl: String,
    val queryTemplate: String,
) {
    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return queryTemplate.replace("%s", encoded)
    }

    companion object {
        const val CUSTOM_ID = "custom"

        val DUCKDUCKGO =
            SearchEngine(
                id = "duckduckgo",
                displayName = "DuckDuckGo",
                homeUrl = "https://duckduckgo.com",
                queryTemplate = "https://duckduckgo.com/?q=%s",
            )
        val SEARXNG =
            SearchEngine(
                id = "searxng",
                displayName = "SearXNG",
                homeUrl = "https://searx.be",
                queryTemplate = "https://searx.be/search?q=%s",
            )
        val BRAVE =
            SearchEngine(
                id = "brave",
                displayName = "Brave Search",
                homeUrl = "https://search.brave.com",
                queryTemplate = "https://search.brave.com/search?q=%s",
            )
        val GOOGLE =
            SearchEngine(
                id = "google",
                displayName = "Google",
                homeUrl = "https://www.google.com",
                queryTemplate = "https://www.google.com/search?q=%s",
            )
        val STARTPAGE =
            SearchEngine(
                id = "startpage",
                displayName = "Startpage",
                homeUrl = "https://www.startpage.com",
                queryTemplate = "https://www.startpage.com/sp/search?query=%s",
            )

        val BUILT_INS = listOf(DUCKDUCKGO, SEARXNG, BRAVE, GOOGLE, STARTPAGE)

        val DEFAULT = DUCKDUCKGO

        fun fromId(id: String?): SearchEngine = BUILT_INS.find { it.id.equals(id, ignoreCase = true) } ?: DEFAULT

        fun isValidTemplate(template: String): Boolean =
            (template.startsWith("http://", ignoreCase = true) || template.startsWith("https://", ignoreCase = true)) &&
                template.contains("%s")

        /** The configured custom engine, or null when no valid template is stored. */
        fun customOrNull(
            name: String,
            template: String,
        ): SearchEngine? {
            if (!isValidTemplate(template)) return null
            return SearchEngine(
                id = CUSTOM_ID,
                displayName = name.ifBlank { hostOf(template) },
                homeUrl = template.substringBefore("?"),
                queryTemplate = template,
            )
        }

        fun resolve(
            selectedId: String?,
            customName: String,
            customUrl: String,
        ): SearchEngine =
            if (selectedId.equals(CUSTOM_ID, ignoreCase = true)) {
                customOrNull(customName, customUrl) ?: fromId(selectedId)
            } else {
                fromId(selectedId)
            }

        private fun hostOf(template: String): String {
            val origin = template.substringBefore("?")
            val withoutScheme = origin.removePrefix("https://").removePrefix("http://")
            return withoutScheme.substringBefore('/').ifBlank { origin }
        }
    }
}
