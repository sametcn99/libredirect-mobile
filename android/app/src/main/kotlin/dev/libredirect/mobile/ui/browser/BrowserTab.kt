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
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val lastRedirect: RedirectNotice? = null,
    /** The link this tab is really "about": the URL before any LibRedirect redirection. */
    val originalUrl: String? = null,
    val isDesktopMode: Boolean = false,
    val favicon: Bitmap? = null,
    /**
     * Scaled screenshot of the page as it last looked on screen, captured in
     * the moment the tab stops being active; null until then (fresh and
     * restored tabs show a placeholder). Session-persist is URL-only, so
     * thumbnails die with the process.
     */
    val thumbnail: Bitmap? = null,
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
