package dev.libredirect.mobile.ui.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.routing.UrlRouter
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class BrowserUiState(
    val tabs: List<BrowserTab> = emptyList(),
    val activeTabId: String = "",
    val isTabsSheetVisible: Boolean = false,
    val activeRedirectNotice: RedirectNotice? = null,
) {
    val activeTab: BrowserTab?
        get() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
}

@Suppress("TooManyFunctions")
class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<LibRedirectApplication>()
    private val manifestRepository = app.manifestRepository
    private val settingsRepository = SettingsRepository(app)

    private val initialTab = BrowserTab(id = UUID.randomUUID().toString(), url = BrowserTab.DEFAULT_HOME_URL)

    private val _uiState =
        MutableStateFlow(
            BrowserUiState(
                tabs = listOf(initialTab),
                activeTabId = initialTab.id,
            ),
        )
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _openBrowserTabEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openBrowserTabEvent: SharedFlow<Unit> = _openBrowserTabEvent.asSharedFlow()

    private var cachedManifest: Manifest? = null
    private var cachedSettings: AppSettings = AppSettings()

    private val webViewPool = mutableMapOf<String, WebView>()

    init {
        viewModelScope.launch {
            cachedManifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
            settingsRepository.settings.collect { settings ->
                cachedSettings = settings
            }
        }
    }

    fun openNewTab(url: String = BrowserTab.DEFAULT_HOME_URL) {
        val newTab = BrowserTab(id = UUID.randomUUID().toString(), url = url, title = "Yeni Sekme")
        _uiState.update { current ->
            current.copy(
                tabs = current.tabs + newTab,
                activeTabId = newTab.id,
                isTabsSheetVisible = false,
            )
        }
        _openBrowserTabEvent.tryEmit(Unit)
    }

    fun openUrlInNewOrActiveTab(url: String) {
        val active = _uiState.value.activeTab
        if (active == null || active.url != BrowserTab.DEFAULT_HOME_URL) {
            val newTab = BrowserTab(id = UUID.randomUUID().toString(), url = url, title = url)
            _uiState.update { current ->
                current.copy(
                    tabs = current.tabs + newTab,
                    activeTabId = newTab.id,
                    isTabsSheetVisible = false,
                )
            }
        } else {
            navigateActiveTab(url)
        }
        _openBrowserTabEvent.tryEmit(Unit)
    }

    fun closeTab(tabId: String) {
        webViewPool.remove(tabId)?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }

        _uiState.update { current ->
            val remaining = current.tabs.filterNot { it.id == tabId }
            if (remaining.isEmpty()) {
                val freshTab = BrowserTab(id = UUID.randomUUID().toString(), url = BrowserTab.DEFAULT_HOME_URL)
                BrowserUiState(
                    tabs = listOf(freshTab),
                    activeTabId = freshTab.id,
                    isTabsSheetVisible = false,
                )
            } else {
                val nextActiveId =
                    if (current.activeTabId == tabId) {
                        remaining.last().id
                    } else {
                        current.activeTabId
                    }
                current.copy(
                    tabs = remaining,
                    activeTabId = nextActiveId,
                )
            }
        }
    }

    fun closeAllTabs() {
        webViewPool.values.forEach { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        webViewPool.clear()

        val freshTab = BrowserTab(id = UUID.randomUUID().toString(), url = BrowserTab.DEFAULT_HOME_URL)
        _uiState.value =
            BrowserUiState(
                tabs = listOf(freshTab),
                activeTabId = freshTab.id,
                isTabsSheetVisible = false,
            )
    }

    fun selectTab(tabId: String) {
        _uiState.update { it.copy(activeTabId = tabId, isTabsSheetVisible = false) }
    }

    fun setTabsSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(isTabsSheetVisible = visible) }
    }

    fun dismissRedirectNotice() {
        _uiState.update { it.copy(activeRedirectNotice = null) }
    }

    fun navigateActiveTab(queryOrUrl: String) {
        val trimmed = queryOrUrl.trim()
        if (trimmed.isEmpty()) return

        val targetUrl =
            when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed

                trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"

                else -> {
                    val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
                    "https://duckduckgo.com/?q=$encoded"
                }
            }

        val resolved = resolveRedirection(targetUrl) ?: targetUrl
        val currentTabId = _uiState.value.activeTabId

        updateTabUrl(currentTabId, resolved)
        val webView = webViewPool[currentTabId]
        if (webView != null) {
            webView.loadUrl(resolved)
        }
    }

    fun reloadActiveTab() {
        val activeId = _uiState.value.activeTabId
        webViewPool[activeId]?.reload()
    }

    fun stopActiveTab() {
        val activeId = _uiState.value.activeTabId
        webViewPool[activeId]?.stopLoading()
        updateTabLoading(activeId, isLoading = false, progress = 0)
    }

    fun goBackActiveTab(): Boolean {
        val activeId = _uiState.value.activeTabId
        val webView = webViewPool[activeId]
        return if (webView != null && webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    fun goForwardActiveTab(): Boolean {
        val activeId = _uiState.value.activeTabId
        val webView = webViewPool[activeId]
        return if (webView != null && webView.canGoForward()) {
            webView.goForward()
            true
        } else {
            false
        }
    }

    fun resolveRedirection(url: String): String? {
        val settings = cachedSettings
        val manifest = cachedManifest
        if (!settings.routingEnabled || manifest == null) return null

        val result = UrlRouter(manifest).resolve(url, settings.routingContext())
        return if (result is RoutingResult.Redirect) {
            val route = manifest.routes.find { it.id == result.routeId }
            val serviceName = route?.name ?: result.routeId
            val frontendName = route?.frontends?.find { it.id == result.frontendId }?.name ?: result.frontendId
            val notice =
                RedirectNotice(
                    originalUrl = url,
                    redirectedUrl = result.redirectedUrl,
                    serviceName = serviceName,
                    frontendName = frontendName,
                )
            _uiState.update { current ->
                current.copy(
                    activeRedirectNotice = notice,
                    tabs =
                        current.tabs.map { tab ->
                            if (tab.id == current.activeTabId) tab.copy(lastRedirect = notice) else tab
                        },
                )
            }
            result.redirectedUrl
        } else {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(
        tabId: String,
        context: Context,
    ): WebView {
        val existing = webViewPool[tabId]
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        val newWebView =
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = BrowserTabWebViewClient(tabId)
                webChromeClient = BrowserTabWebChromeClient(tabId)
            }

        val tab = _uiState.value.tabs.find { it.id == tabId }
        val target = tab?.url ?: BrowserTab.DEFAULT_HOME_URL
        val resolved = resolveRedirection(target) ?: target
        newWebView.loadUrl(resolved)

        webViewPool[tabId] = newWebView
        return newWebView
    }

    private fun updateTabUrl(
        tabId: String,
        url: String,
    ) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId) tab.copy(url = url) else tab
                    },
            )
        }
    }

    private fun updateTabTitle(
        tabId: String,
        title: String,
    ) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId) tab.copy(title = title) else tab
                    },
            )
        }
    }

    private fun updateTabLoading(
        tabId: String,
        isLoading: Boolean,
        progress: Int,
    ) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId) tab.copy(isLoading = isLoading, progress = progress) else tab
                    },
            )
        }
    }

    private fun updateTabNavigation(
        tabId: String,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId) {
                            tab.copy(canGoBack = canGoBack, canGoForward = canGoForward)
                        } else {
                            tab
                        }
                    },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        webViewPool.values.forEach { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        webViewPool.clear()
    }

    private inner class BrowserTabWebViewClient(private val tabId: String) : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val uri = request?.url ?: return false
            val urlString = uri.toString()

            val isHttp =
                urlString.startsWith("http://", ignoreCase = true) ||
                    urlString.startsWith("https://", ignoreCase = true)

            return if (!isHttp) {
                try {
                    val intent =
                        Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    view?.context?.startActivity(intent)
                } catch (_: Exception) {
                    // Ignore intents that cannot be resolved
                }
                true
            } else {
                val redirected = resolveRedirection(urlString)
                if (redirected != null && redirected != urlString) {
                    view?.loadUrl(redirected)
                    true
                } else {
                    false
                }
            }
        }

        override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: Bitmap?,
        ) {
            super.onPageStarted(view, url, favicon)
            if (url != null) {
                val redirected = resolveRedirection(url)
                if (redirected != null && redirected != url) {
                    view?.stopLoading()
                    view?.loadUrl(redirected)
                    return
                }
                updateTabUrl(tabId, url)
                updateTabLoading(tabId, isLoading = true, progress = 10)
            }
        }

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            super.onPageFinished(view, url)
            if (url != null) {
                updateTabUrl(tabId, url)
                updateTabTitle(tabId, view?.title ?: url)
            }
            updateTabLoading(tabId, isLoading = false, progress = 100)
            updateTabNavigation(
                tabId = tabId,
                canGoBack = view?.canGoBack() == true,
                canGoForward = view?.canGoForward() == true,
            )
        }
    }

    private inner class BrowserTabWebChromeClient(private val tabId: String) : WebChromeClient() {
        override fun onProgressChanged(
            view: WebView?,
            newProgress: Int,
        ) {
            super.onProgressChanged(view, newProgress)
            updateTabLoading(tabId, isLoading = newProgress < 100, progress = newProgress)
            updateTabNavigation(
                tabId = tabId,
                canGoBack = view?.canGoBack() == true,
                canGoForward = view?.canGoForward() == true,
            )
        }

        override fun onReceivedTitle(
            view: WebView?,
            title: String?,
        ) {
            super.onReceivedTitle(view, title)
            if (!title.isNullOrBlank()) {
                updateTabTitle(tabId, title)
            }
        }
    }
}
