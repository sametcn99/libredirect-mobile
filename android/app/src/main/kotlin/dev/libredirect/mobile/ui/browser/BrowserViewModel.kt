package dev.libredirect.mobile.ui.browser

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.R
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
import java.util.UUID

data class BrowserUiState(
    val tabs: List<BrowserTab> = emptyList(),
    val activeTabId: String = "",
    val isTabsSheetVisible: Boolean = false,
    val activeRedirectNotice: RedirectNotice? = null,
    val bookmarks: List<BrowserBookmark> = emptyList(),
    val history: List<BrowserHistoryItem> = emptyList(),
    val isBookmarksHistorySheetVisible: Boolean = false,
    val bookmarksHistoryInitialTab: Int = 0,
    val searchEngine: SearchEngine = SearchEngine.DEFAULT,
    val isSearchEngineDialogVisible: Boolean = false,
    val customSearchEngineName: String = "",
    val customSearchEngineUrl: String = "",
    val isFindInPageVisible: Boolean = false,
    val findQuery: String = "",
    val findMatchCount: Int = 0,
    val findActiveMatchIndex: Int = 0,
) {
    val activeTab: BrowserTab?
        get() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    val isCurrentPageBookmarked: Boolean
        get() {
            val url = activeTab?.url ?: return false
            if (url == BrowserTab.DEFAULT_HOME_URL || url.isBlank()) return false
            return bookmarks.any { it.url == url }
        }
}

@Suppress("TooManyFunctions")
class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<LibRedirectApplication>()
    private val manifestRepository = app.manifestRepository
    private val settingsRepository = SettingsRepository(app)
    private val browserDataRepository = BrowserDataRepository(app)

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
                _uiState.update {
                    it.copy(
                        searchEngine =
                            SearchEngine.resolve(
                                selectedId = settings.searchEngine,
                                customName = settings.customSearchEngineName,
                                customUrl = settings.customSearchEngineUrl,
                            ),
                        customSearchEngineName = settings.customSearchEngineName,
                        customSearchEngineUrl = settings.customSearchEngineUrl,
                    )
                }
            }
        }
        viewModelScope.launch {
            browserDataRepository.bookmarks.collect { bookmarks ->
                _uiState.update { it.copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch {
            browserDataRepository.history.collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
    }

    fun openNewTab(url: String? = null) {
        val targetUrl = url ?: _uiState.value.searchEngine.homeUrl
        val newTab = BrowserTab(id = UUID.randomUUID().toString(), url = targetUrl, title = "Yeni Sekme")
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
                val freshTab =
                    BrowserTab(
                        id = UUID.randomUUID().toString(),
                        url = current.searchEngine.homeUrl,
                    )
                BrowserUiState(
                    tabs = listOf(freshTab),
                    activeTabId = freshTab.id,
                    isTabsSheetVisible = false,
                    searchEngine = current.searchEngine,
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

        val freshTab =
            BrowserTab(
                id = UUID.randomUUID().toString(),
                url = _uiState.value.searchEngine.homeUrl,
            )
        _uiState.value =
            BrowserUiState(
                tabs = listOf(freshTab),
                activeTabId = freshTab.id,
                isTabsSheetVisible = false,
                searchEngine = _uiState.value.searchEngine,
            )
    }

    fun selectTab(tabId: String) {
        webViewPool[_uiState.value.activeTabId]?.clearMatches()
        _uiState.update {
            it.copy(
                activeTabId = tabId,
                isTabsSheetVisible = false,
                isFindInPageVisible = false,
                findMatchCount = 0,
                findActiveMatchIndex = 0,
            )
        }
    }

    fun setTabsSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(isTabsSheetVisible = visible) }
    }

    fun setBookmarksHistorySheetVisible(
        visible: Boolean,
        initialTab: Int = 0,
    ) {
        _uiState.update {
            it.copy(
                isBookmarksHistorySheetVisible = visible,
                bookmarksHistoryInitialTab = initialTab,
            )
        }
    }

    fun setSearchEngineDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(isSearchEngineDialogVisible = visible) }
    }

    fun setSearchEngine(engine: SearchEngine) {
        viewModelScope.launch {
            settingsRepository.setSearchEngine(engine.id)
        }
    }

    fun saveCustomSearchEngine(
        name: String,
        template: String,
    ) {
        viewModelScope.launch {
            settingsRepository.setCustomSearchEngine(name.trim(), template.trim())
            settingsRepository.setSearchEngine(SearchEngine.CUSTOM_ID)
        }
    }

    fun setFindInPageVisible(visible: Boolean) {
        if (!visible) {
            webViewPool[_uiState.value.activeTabId]?.clearMatches()
        }
        _uiState.update {
            it.copy(
                isFindInPageVisible = visible,
                findMatchCount = 0,
                findActiveMatchIndex = 0,
            )
        }
    }

    fun findInPage(query: String) {
        _uiState.update { it.copy(findQuery = query) }
        val webView = webViewPool[_uiState.value.activeTabId] ?: return
        if (query.isBlank()) {
            webView.clearMatches()
            _uiState.update { it.copy(findMatchCount = 0, findActiveMatchIndex = 0) }
        } else {
            webView.findAllAsync(query)
        }
    }

    fun findNextInPage(forward: Boolean) {
        webViewPool[_uiState.value.activeTabId]?.findNext(forward)
    }

    fun clearBrowsingData() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
        webViewPool.values.forEach { webView ->
            webView.clearCache(true)
        }
    }

    fun toggleBookmarkActivePage() {
        val active = _uiState.value.activeTab ?: return
        val url = active.url
        if (url.isBlank() || url == BrowserTab.DEFAULT_HOME_URL) return

        val isBookmarked = _uiState.value.isCurrentPageBookmarked
        viewModelScope.launch {
            if (isBookmarked) {
                browserDataRepository.removeBookmarkByUrl(url)
            } else {
                val title = active.title.ifBlank { url }
                browserDataRepository.addBookmark(title, url)
            }
        }
    }

    fun toggleDesktopModeActiveTab() {
        val active = _uiState.value.activeTab ?: return
        val newMode = !active.isDesktopMode
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == active.id) tab.copy(isDesktopMode = newMode) else tab
                    },
            )
        }
        val webView = webViewPool[active.id]
        if (webView != null) {
            applyDesktopMode(webView, newMode)
            webView.reload()
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch {
            browserDataRepository.removeBookmark(id)
        }
    }

    fun importBookmarks(
        content: String,
        onComplete: ((Int) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val parsed = BookmarkCodec.parseBookmarks(content)
            val added = browserDataRepository.importBookmarks(parsed)
            onComplete?.invoke(added)
        }
    }

    fun exportBookmarksHtml(): String = BookmarkCodec.exportToHtml(_uiState.value.bookmarks)

    fun exportBookmarksJson(): String = BookmarkCodec.exportToJson(_uiState.value.bookmarks)

    fun deleteHistoryItem(id: String) {
        viewModelScope.launch {
            browserDataRepository.removeHistoryItem(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            browserDataRepository.clearHistory()
        }
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

                else -> _uiState.value.searchEngine.buildSearchUrl(trimmed)
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

        val tab = _uiState.value.tabs.find { it.id == tabId }
        val isDesktop = tab?.isDesktopMode == true

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
                applyDesktopMode(this, isDesktop)
                setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                    _uiState.update {
                        it.copy(
                            findActiveMatchIndex = activeMatchOrdinal + 1,
                            findMatchCount = numberOfMatches,
                        )
                    }
                }
                webViewClient = BrowserTabWebViewClient(tabId)
                webChromeClient = BrowserTabWebChromeClient(tabId)
                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    enqueueDownload(url, userAgent, contentDisposition, mimeType)
                }
            }

        val target = tab?.url ?: BrowserTab.DEFAULT_HOME_URL
        val resolved = resolveRedirection(target) ?: target
        newWebView.loadUrl(resolved)

        webViewPool[tabId] = newWebView
        return newWebView
    }

    /**
     * Hands downloads to the system DownloadManager so they show up in the
     * system's Downloads list with a progress notification. On API < 29 the
     * shared Downloads directory needs a storage permission we don't request;
     * there the file falls back to the app-specific downloads directory.
     */
    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request =
            DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            }
        try {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } catch (_: SecurityException) {
            request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        try {
            app.getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(app, R.string.browser_download_started, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(app, R.string.browser_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyDesktopMode(
        webView: WebView,
        isDesktopMode: Boolean,
    ) {
        val userAgent =
            if (isDesktopMode) {
                BrowserTab.DESKTOP_USER_AGENT
            } else {
                WebSettings.getDefaultUserAgent(webView.context)
            }
        webView.settings.userAgentString = userAgent
        webView.settings.useWideViewPort = isDesktopMode
        webView.settings.loadWithOverviewMode = isDesktopMode
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
            if (tabId == _uiState.value.activeTabId) {
                _uiState.update { it.copy(findMatchCount = 0, findActiveMatchIndex = 0) }
            }
        }

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            super.onPageFinished(view, url)
            if (url != null) {
                val pageTitle = view?.title ?: url
                updateTabUrl(tabId, url)
                updateTabTitle(tabId, pageTitle)
                if (url != BrowserTab.DEFAULT_HOME_URL && !url.equals("about:blank", ignoreCase = true)) {
                    viewModelScope.launch {
                        browserDataRepository.addHistory(pageTitle, url)
                    }
                }
            }
            updateTabLoading(tabId, isLoading = false, progress = 100)
            updateTabNavigation(
                tabId = tabId,
                canGoBack = view?.canGoBack() == true,
                canGoForward = view?.canGoForward() == true,
            )
            val findState = _uiState.value
            if (tabId == findState.activeTabId && findState.isFindInPageVisible && findState.findQuery.isNotBlank()) {
                view?.findAllAsync(findState.findQuery)
            }
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
