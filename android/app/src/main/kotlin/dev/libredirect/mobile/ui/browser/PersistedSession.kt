package dev.libredirect.mobile.ui.browser

import kotlinx.serialization.Serializable

/**
 * One restorable tab: its current URL plus the redirect metadata needed for
 * "try another instance" to keep working after a process death. WebView
 * back/forward history is deliberately not persisted — only what a fresh
 * load of [url] needs.
 */
@Serializable
data class PersistedTab(
    val url: String,
    val originalUrl: String? = null,
    val routeId: String? = null,
    val frontendId: String? = null,
    val instance: String? = null,
    /** When the tab was created; drives the auto-close-old-tabs setting (4.5). */
    val createdAtEpochMillis: Long = 0L,
    val group: String? = null,
)

@Serializable
data class PersistedSession(
    val tabs: List<PersistedTab> = emptyList(),
    val activeIndex: Int = 0,
)
