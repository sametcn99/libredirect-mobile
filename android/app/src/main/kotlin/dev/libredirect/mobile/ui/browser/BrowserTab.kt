package dev.libredirect.mobile.ui.browser

import android.graphics.Bitmap
import java.util.UUID

data class RedirectNotice(
    val originalUrl: String,
    val redirectedUrl: String,
    val serviceName: String,
    val frontendName: String,
    val routeId: String? = null,
    val frontendId: String? = null,
    val instance: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = DEFAULT_HOME_URL,
    /** Page title; blank until the page reports one. UI layers show a localized "new tab" fallback. */
    val title: String = "",
    /** When the tab came into existence; persisted for auto-close-old-tabs (4.5). */
    val createdAt: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val lastRedirect: RedirectNotice? = null,
    /** The link this tab is really "about": the URL before any LibRedirect redirection. */
    val originalUrl: String? = null,
    val isDesktopMode: Boolean = false,
    /**
     * Private tab: never written to history, session persistence or bookmarks,
     * and — when the WebView Profile API is available — isolated in its own
     * ephemeral cookie/storage profile that is deleted with the last tab.
     */
    val isIncognito: Boolean = false,
    /** True while the tab shows the in-app error page instead of rendered content. */
    val pageError: Boolean = false,
    val favicon: Bitmap? = null,
    /**
     * Scaled screenshot of the page as it last looked on screen, captured in
     * the moment the tab stops being active; null until then (fresh and
     * restored tabs show a placeholder). Session-persist is URL-only, so
     * thumbnails die with the process.
     */
    val thumbnail: Bitmap? = null,
    /** Monotonic marker for tab-switcher sorting (4.5); 0 = never activated. */
    val lastActiveAt: Long = 0L,
    /** Optional user-assigned tab group name (4.5); null = ungrouped. */
    val group: String? = null,
    /** Requests this tab's page was denied by the content blocker, this load (4.2). */
    val blockedCount: Int = 0,
    /** True while the tab renders the extracted reader view (4.4). */
    val isReaderMode: Boolean = false,
    /** Automatic failover attempts already spent on the current routed load (2.2). */
    val failoverAttempts: Int = 0,
    /** Auto-hide toolbar state with hysteresis; flips only on scroll-direction change (4.5). */
    val toolbarHidden: Boolean = false,
) {
    /** Host shown when the address bar isn't being edited, e.g. "example.com". */
    val displayHost: String
        get() = hostOf(url)

    val isSecure: Boolean
        get() = url.startsWith("https://", ignoreCase = true)

    companion object {
        const val DEFAULT_HOME_URL = "https://duckduckgo.com"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        fun hostOf(url: String): String {
            val withoutScheme = url.substringAfter("://", url)
            val host =
                withoutScheme
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .substringAfter('@')
                    .removePrefix("www.")
            return host.ifBlank { url }
        }
    }
}
