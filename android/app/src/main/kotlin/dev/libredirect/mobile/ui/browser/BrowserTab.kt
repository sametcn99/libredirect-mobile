package dev.libredirect.mobile.ui.browser

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
) {
    companion object {
        const val DEFAULT_HOME_URL = "https://duckduckgo.com"
    }
}
