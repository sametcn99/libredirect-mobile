package dev.libredirect.mobile.ui.home

import dev.libredirect.mobile.ui.ServiceUiState

/** Sort options for the home screen's service list. */
enum class ServiceSort(val label: String) {
    POPULARITY("Popularity"),
    NAME_ASC("A to Z"),
}

/** Applies a [ServiceSort] to the service list shown on the home screen. */
object ServiceSorter {
    /**
     * Hardcoded popularity ranking for the most-used upstream services.
     * Unlisted services keep their manifest order after the ranked ones.
     */
    private val POPULARITY_ORDER =
        listOf(
            "YouTube",
            "Instagram",
            "Reddit",
            "Twitter",
            "TikTok",
            "Twitch",
            "Wikipedia",
            "Pinterest",
            "Medium",
            "Imgur",
            "Tumblr",
            "Threads",
            "SoundCloud",
            "Stack Overflow",
            "GitHub",
            "Maps",
            "Translate",
            "Search",
            "Quora",
            "IMDb",
            "Wayback Machine",
        )

    fun sort(
        services: List<ServiceUiState>,
        sort: ServiceSort,
    ): List<ServiceUiState> =
        when (sort) {
            ServiceSort.POPULARITY -> services.sortedBy { popularityRank(it.name) }
            ServiceSort.NAME_ASC -> services.sortedBy { it.name.lowercase() }
        }

    /** Unranked names all share the fallback rank, so a stable sort keeps manifest order. */
    private fun popularityRank(name: String): Int {
        val index = POPULARITY_ORDER.indexOfFirst { it.equals(name, ignoreCase = true) }
        return if (index >= 0) index else POPULARITY_ORDER.size
    }
}
