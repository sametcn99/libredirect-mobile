package dev.libredirect.mobile.ui.services

import androidx.annotation.StringRes
import dev.libredirect.mobile.R

/**
 * Broad service categories for discovery (5.6). The manifest carries no
 * category metadata, so well-known route ids are classified statically and
 * everything else lands in OTHER — never guessed per-service at runtime.
 */
enum class ServiceCategory(
    @StringRes val labelRes: Int,
) {
    ALL(R.string.service_category_all),
    VIDEO(R.string.service_category_video),
    SOCIAL(R.string.service_category_social),
    SEARCH(R.string.service_category_search),
    TRANSLATE(R.string.service_category_translate),
    NEWS(R.string.service_category_news),
    MAPS(R.string.service_category_maps),
    DEV(R.string.service_category_dev),
    MUSIC(R.string.service_category_music),
    OTHER(R.string.service_category_other),
    ;

    companion object {
        /** Display categories (without the ALL sentinel) in chip order. */
        val DISPLAY: List<ServiceCategory> = entries.filter { it != ALL }

        private val BY_ROUTE_ID =
            mapOf(
                "youtube" to VIDEO,
                "peertube" to VIDEO,
                "twitch" to VIDEO,
                "twitch-vod" to VIDEO,
                "vk" to VIDEO,
                "reddit" to SOCIAL,
                "twitter" to SOCIAL,
                "x" to SOCIAL,
                "instagram" to SOCIAL,
                "tiktok" to SOCIAL,
                "facebook" to SOCIAL,
                "threads" to SOCIAL,
                "bluesky" to SOCIAL,
                "mastodon" to SOCIAL,
                "imgur" to SOCIAL,
                "imdb" to OTHER,
                "wikipedia" to NEWS,
                "medium" to NEWS,
                "quora" to NEWS,
                "google-search" to SEARCH,
                "search" to SEARCH,
                "duckduckgo" to SEARCH,
                "translate" to TRANSLATE,
                "google-translate" to TRANSLATE,
                "google-maps" to MAPS,
                "maps" to MAPS,
                "openstreetmap" to MAPS,
                "github" to DEV,
                "stack-overflow" to DEV,
                "gitlab" to DEV,
                "hackernews" to DEV,
                "spotify" to MUSIC,
                "soundcloud" to MUSIC,
                "bandcamp" to MUSIC,
                "music" to MUSIC,
            )

        fun of(routeId: String): ServiceCategory = BY_ROUTE_ID[routeId] ?: OTHER
    }
}
