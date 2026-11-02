package dev.libredirect.mobile.ui.browser

import android.graphics.Bitmap
import java.util.UUID

data class RedirectNotice(
    val originalUrl: String,
    val redirectedUrl: String,
    val serviceName: String,
    val frontendName: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = DEFAULT_HOME_URL,
    val title: String = "Yeni Sekme",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val lastRedirect: RedirectNotice? = null,
    val isDesktopMode: Boolean = false,
    val favicon: Bitmap? = null,
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
