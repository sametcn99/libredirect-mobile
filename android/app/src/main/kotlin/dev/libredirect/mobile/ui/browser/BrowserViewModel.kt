package dev.libredirect.mobile.ui.browser

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.routing.RouteMatcher
import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.routing.UrlRouter
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Instance choices offered for the active tab's original link. Built when the
 * browser menu opens; [currentIndex] is -1 when the instance in use is unknown.
 */
data class InstanceAlternatives(
    val routeId: String,
    val routeName: String,
    val frontendId: String,
    val frontendName: String,
    val instances: List<String>,
    val currentIndex: Int,
)

data class BrowserUiState(
    val tabs: List<BrowserTab> = emptyList(),
    val activeTabId: String = "",
    val isTabsSheetVisible: Boolean = false,
    /** Notice timestamps the user (or the auto-dismiss) has already seen. */
    val dismissedNoticeTimestamps: Set<Long> = emptySet(),
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
    val isMenuSheetVisible: Boolean = false,
    val isClearDataConfirmVisible: Boolean = false,
    val instanceAlternatives: InstanceAlternatives? = null,
    val isInstancePickerVisible: Boolean = false,
    /** Per-instance time-to-first-byte in ms; [LATENCY_FAILED] marks unreachable hosts. Empty until measured. */
    val instanceLatencies: Map<String, Long> = emptyMap(),
    val isMeasuringInstances: Boolean = false,
    val pageTheme: PageTheme = PageTheme.DEFAULT,
    val autoDarken: Boolean = true,
) {
    val activeTab: BrowserTab?
        get() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    /**
     * The redirect banner belongs to the active tab, not to the screen: it
     * derives from that tab's [BrowserTab.lastRedirect], so switching tabs
     * never shows another tab's banner. A notice dismissed here stays hidden
     * until a new redirect replaces it.
     */
    val activeRedirectNotice: RedirectNotice?
        get() = activeTab?.lastRedirect?.takeIf { it.timestamp !in dismissedNoticeTimestamps }

    val isCurrentPageBookmarked: Boolean
        get() {
            val url = activeTab?.url ?: return false
            if (url == BrowserTab.DEFAULT_HOME_URL || url.isBlank()) return false
            return bookmarks.any { it.url == url }
        }
}

@Suppress("TooManyFunctions", "LargeClass")
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

    /**
     * One-shot flag: the browser screen should be brought to the front (e.g. a
     * link arrived from outside the app). Backed by a StateFlow instead of a
     * fire-and-forget event so a request emitted before the UI subscribes —
     * cold start handling an intent — is still observed. The collector
     * consumes it via [consumeBrowserFocusRequest], so the replay an Activity
     * recreation receives (the collector restarts and the StateFlow replays
     * its latest value) cannot re-trigger an already-handled request.
     */
    private val _browserFocusRequested = MutableStateFlow(false)
    val browserFocusRequested: StateFlow<Boolean> = _browserFocusRequested.asStateFlow()

    private fun requestBrowserFocus() {
        _browserFocusRequested.value = true
    }

    /** Clears a handled focus request; see [browserFocusRequested]. */
    fun consumeBrowserFocusRequest() {
        _browserFocusRequested.value = false
    }

    /**
     * Per tab: URLs the tab is already heading to as the resolved destination
     * of a LibRedirect redirect. The WebView client must not re-resolve them
     * (they are frontend origins, not original service links) and must keep
     * the tab's original-link metadata intact when they load.
     */
    private val pendingRedirectTargets = mutableMapOf<String, MutableSet<String>>()

    private var cachedManifest: Manifest? = null
    private var cachedSettings: AppSettings = AppSettings()

    /**
     * Completed once the manifest and the first settings value have landed on
     * a cold start. Until then [resolveRedirection] would route against
     * defaults (or skip routing on a null manifest), so initial page loads
     * wait for it instead of racing the first seconds of startup.
     */
    private val initialStateLoaded = CompletableDeferred<Unit>()

    private var sessionPersistJob: Job? = null

    private val webViewPool = mutableMapOf<String, WebView>()

    /** Whether each pooled WebView was created with a dark theme; see [WebViewTheming]. */
    private val webViewDarkModes = mutableMapOf<String, Boolean>()

    /** Last auto-darken value pushed to the WebViews, so unrelated settings changes don't reload pages. */
    private var appliedAutoDarken: Boolean? = null

    init {
        viewModelScope.launch {
            restoreSession()
            cachedManifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
            // Take the first settings value synchronously so no navigation can
            // resolve against defaults while DataStore is still reading.
            cachedSettings = settingsRepository.settings.first()
            initialStateLoaded.complete(Unit)
            refreshManifestDerivedState()
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
                        pageTheme = PageTheme.fromId(settings.browserPageTheme),
                        autoDarken = settings.browserAutoDarken,
                    )
                }
                applyAutoDarkenToAll(settings.browserAutoDarken)
            }
        }
        viewModelScope.launch {
            // Follow manifest changes (instance refresh, custom services) so
            // the browser keeps routing against the current manifest instead
            // of the snapshot taken on cold start.
            manifestRepository.activeManifestUpdates.collect { manifest ->
                cachedManifest = manifest
                refreshManifestDerivedState()
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

    /** Re-derives state that depends on the manifest (notice names, instance alternatives). */
    private fun refreshManifestDerivedState() {
        enrichRedirectNotices()
        if (_uiState.value.isMenuSheetVisible) {
            _uiState.update { it.copy(instanceAlternatives = computeInstanceAlternatives()) }
        }
    }

    fun openNewTab(url: String? = null) {
        val targetUrl = url ?: _uiState.value.searchEngine.homeUrl
        val newTab = BrowserTab(id = UUID.randomUUID().toString(), url = targetUrl)
        _uiState.update { current ->
            current
                .copy(
                    tabs = current.tabs + newTab,
                    activeTabId = newTab.id,
                ).withOverlaysClosed()
        }
        requestBrowserFocus()
        scheduleSessionPersist()
    }

    /**
     * A link arriving from outside the app (share target, link handler) always
     * gets its own new tab and brings the browser screen to the front. The tab
     * keeps the original (pre-redirection) link plus the route/frontend/instance
     * that produced the destination, so "try another instance" can re-route it.
     */
    fun openExternalLinkInNewTab(
        url: String,
        originalUrl: String? = null,
        routeId: String? = null,
        frontendId: String? = null,
        instance: String? = null,
    ) {
        val origin = originalUrl ?: url
        // The same link arriving twice (e.g. two taps on one share) should
        // refocus the existing tab instead of piling up duplicates — but only
        // while the tab still sits on that link. A tab that has navigated on
        // since is about something else now, and matching it on the stale
        // original link would swallow the re-opened link entirely.
        _uiState.value.tabs
            .find { it.url == url }
            ?.let { existing ->
                webViewPool[_uiState.value.activeTabId]?.clearMatches()
                _uiState.update { it.copy(activeTabId = existing.id).withOverlaysClosed() }
                requestBrowserFocus()
                scheduleSessionPersist()
                return
            }
        val notice =
            routeId?.let { rid ->
                val (serviceName, frontendName) = routeNames(cachedManifest, rid, frontendId)
                RedirectNotice(
                    originalUrl = origin,
                    redirectedUrl = url,
                    serviceName = serviceName,
                    frontendName = frontendName,
                    routeId = rid,
                    frontendId = frontendId,
                    instance = instance,
                )
            }
        val newTab =
            BrowserTab(
                id = UUID.randomUUID().toString(),
                url = url,
                title = BrowserTab.hostOf(url),
                lastRedirect = notice,
                originalUrl = origin,
            )
        if (notice != null) {
            pendingRedirectTargets.getOrPut(newTab.id) { mutableSetOf() }.add(url)
        }
        _uiState.update { current ->
            current
                .copy(
                    tabs = current.tabs + newTab,
                    activeTabId = newTab.id,
                ).withOverlaysClosed()
        }
        requestBrowserFocus()
        scheduleSessionPersist()
    }

    /**
     * Overlays are screen-global: anything that refocuses or creates a tab
     * must not leave an open sheet or panel stranded in front of the new page.
     */
    private fun BrowserUiState.withOverlaysClosed(): BrowserUiState =
        copy(
            isTabsSheetVisible = false,
            isMenuSheetVisible = false,
            isBookmarksHistorySheetVisible = false,
            isSearchEngineDialogVisible = false,
            isFindInPageVisible = false,
            isInstancePickerVisible = false,
            isClearDataConfirmVisible = false,
        )

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
        requestBrowserFocus()
        scheduleSessionPersist()
    }

    fun closeTab(tabId: String) {
        destroyWebView(tabId)
        pendingRedirectTargets.remove(tabId)

        _uiState.update { current ->
            val remaining = current.tabs.filterNot { it.id == tabId }
            if (remaining.isEmpty()) {
                val freshTab =
                    BrowserTab(
                        id = UUID.randomUUID().toString(),
                        url = current.searchEngine.homeUrl,
                    )
                current.copy(
                    tabs = listOf(freshTab),
                    activeTabId = freshTab.id,
                    isTabsSheetVisible = false,
                    isFindInPageVisible = false,
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
        scheduleSessionPersist(immediate = true)
    }

    fun closeAllTabs() {
        webViewPool.keys.toList().forEach(::destroyWebView)
        pendingRedirectTargets.clear()

        val freshTab =
            BrowserTab(
                id = UUID.randomUUID().toString(),
                url = _uiState.value.searchEngine.homeUrl,
            )
        _uiState.update { current ->
            current.copy(
                tabs = listOf(freshTab),
                activeTabId = freshTab.id,
                isTabsSheetVisible = false,
                isFindInPageVisible = false,
            )
        }
        scheduleSessionPersist(immediate = true)
    }

    private fun destroyWebView(tabId: String) {
        webViewDarkModes.remove(tabId)
        webViewPool.remove(tabId)?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
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
        scheduleSessionPersist()
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

    fun setMenuSheetVisible(visible: Boolean) {
        _uiState.update {
            it.copy(
                isMenuSheetVisible = visible,
                instanceAlternatives = if (visible) computeInstanceAlternatives() else null,
            )
        }
    }

    fun setInstancePickerVisible(visible: Boolean) {
        _uiState.update {
            if (visible) {
                it.copy(isInstancePickerVisible = true, instanceLatencies = emptyMap(), isMeasuringInstances = false)
            } else {
                it.copy(isInstancePickerVisible = false)
            }
        }
    }

    /**
     * User-initiated probe of the current frontend's instances: a bounded
     * concurrency pool of HEAD requests measures time-to-first-byte so the
     * picker can rank instances and suggest the fastest one to pin. Runs
     * only on request — never in the background.
     */
    fun measureInstanceLatency() {
        val alternatives = _uiState.value.instanceAlternatives ?: return
        if (_uiState.value.isMeasuringInstances) return
        _uiState.update { it.copy(isMeasuringInstances = true, instanceLatencies = emptyMap()) }
        viewModelScope.launch {
            val results =
                withContext(Dispatchers.IO) {
                    measureLatencies(alternatives.instances)
                }
            _uiState.update { it.copy(instanceLatencies = results, isMeasuringInstances = false) }
        }
    }

    private suspend fun measureLatencies(instances: List<String>): Map<String, Long> =
        coroutineScope {
            val client =
                OkHttpClient
                    .Builder()
                    .connectTimeout(LATENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(LATENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
            val semaphore = Semaphore(LATENCY_PROBE_CONCURRENCY)
            instances
                .map { instance ->
                    async { instance to semaphore.withPermit { probeInstance(client, instance) } }
                }.awaitAll()
                .toMap()
        }

    @Suppress("TooGenericExceptionCaught")
    private fun probeInstance(
        client: OkHttpClient,
        instance: String,
    ): Long =
        try {
            val request = Request.Builder().url(instance).head().build()
            val startNanos = System.nanoTime()
            client.newCall(request).execute().use {
                (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            }
        } catch (_: Exception) {
            LATENCY_FAILED_MILLIS
        }

    /**
     * Pooled WebViews keep the theme they were created with; the screen
     * re-keys its AndroidView on the effective page theme, and
     * [getOrCreateWebView] rebuilds any WebView whose theme no longer matches.
     */
    fun setPageTheme(theme: PageTheme) {
        _uiState.update { it.copy(pageTheme = theme) }
        viewModelScope.launch {
            settingsRepository.setBrowserPageTheme(theme.id)
        }
    }

    fun setAutoDarken(enabled: Boolean) {
        _uiState.update { it.copy(autoDarken = enabled) }
        applyAutoDarkenToAll(enabled)
        viewModelScope.launch {
            settingsRepository.setBrowserAutoDarken(enabled)
        }
    }

    private fun applyAutoDarkenToAll(enabled: Boolean) {
        if (appliedAutoDarken == enabled) return
        val isFirstApply = appliedAutoDarken == null
        appliedAutoDarken = enabled
        webViewPool.forEach { (tabId, webView) ->
            val dark = webViewDarkModes[tabId] == true
            WebViewTheming.applyAutoDarken(webView, dark, enabled)
            // Darkening only affects pages rendered with a dark theme; reload those so it takes effect.
            if (dark && !isFirstApply) webView.reload()
        }
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
        _uiState.update { it.copy(isClearDataConfirmVisible = false) }
    }

    fun setClearDataConfirmVisible(visible: Boolean) {
        _uiState.update { it.copy(isClearDataConfirmVisible = visible) }
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

    /**
     * Hides the active tab's redirect banner by remembering its timestamp;
     * the tab's [BrowserTab.lastRedirect] metadata (original link, instance)
     * stays intact for "try another instance". A later redirect on the same
     * tab carries a fresh timestamp and shows again.
     */
    fun dismissRedirectNotice() {
        _uiState.update { current ->
            val timestamp = current.activeTab?.lastRedirect?.timestamp ?: return@update current
            current.copy(dismissedNoticeTimestamps = current.dismissedNoticeTimestamps + timestamp)
        }
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

        val currentTabId = _uiState.value.activeTabId
        val resolved = resolveRedirection(targetUrl, currentTabId)
        if (resolved == null) {
            // A fresh, unredirected navigation: the tab is now about this link.
            _uiState.update { current ->
                current.copy(
                    tabs =
                        current.tabs.map { tab ->
                            if (tab.id == currentTabId) {
                                tab.copy(originalUrl = trimmed, lastRedirect = null)
                            } else {
                                tab
                            }
                        },
                )
            }
        }

        updateTabUrl(currentTabId, resolved ?: targetUrl)
        val webView = webViewPool[currentTabId]
        if (webView != null) {
            webView.loadUrl(resolved ?: targetUrl)
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

    @Suppress("ReturnCount")
    private fun resolveRedirection(
        url: String,
        tabId: String,
    ): String? {
        val settings = cachedSettings
        val manifest = cachedManifest
        if (!settings.routingEnabled || manifest == null) return null

        val result = UrlRouter(manifest).resolve(url, settings.routingContext())
        if (result !is RoutingResult.Redirect) return null

        val route = manifest.routes.find { it.id == result.routeId }
        val serviceName = route?.name ?: result.routeId
        val frontendName = route?.frontends?.find { it.id == result.frontendId }?.name ?: result.frontendId
        val notice =
            RedirectNotice(
                originalUrl = url,
                redirectedUrl = result.redirectedUrl,
                serviceName = serviceName,
                frontendName = frontendName,
                routeId = result.routeId,
                frontendId = result.frontendId,
                instance = result.instance,
            )
        pendingRedirectTargets.getOrPut(tabId) { mutableSetOf() }.add(result.redirectedUrl)
        _uiState.update { current ->
            // Attribute the redirect to the tab that navigated: a background
            // tab's WebView client also resolves here and must never touch the
            // active tab's metadata. The banner derives from the active tab,
            // so a background redirect cannot hijack it either.
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId) {
                            tab.copy(lastRedirect = notice, originalUrl = url)
                        } else {
                            tab
                        }
                    },
            )
        }
        return result.redirectedUrl
    }

    /**
     * Re-routes the active tab's original link through another instance of its
     * frontend. A null [selectedInstance] picks a random instance other than
     * the one currently in use (falling back to any instance when the frontend
     * only knows one).
     */
    @Suppress("ReturnCount")
    fun tryAnotherInstance(selectedInstance: String?) {
        val alternatives = _uiState.value.instanceAlternatives ?: return
        val manifest = cachedManifest ?: return
        val tab = _uiState.value.activeTab ?: return
        val original = tab.originalUrl ?: return
        setInstancePickerVisible(false)

        val target =
            selectedInstance ?: alternatives.instances
                .filterIndexed { index, _ -> index != alternatives.currentIndex }
                .ifEmpty { alternatives.instances }
                .randomOrNull() ?: return

        val context =
            cachedSettings
                .routingContext()
                .copy(instanceOverrides = mapOf("${alternatives.routeId}/${alternatives.frontendId}" to target))
        val result = UrlRouter(manifest).resolve(original, context)
        if (result !is RoutingResult.Redirect || result.redirectedUrl == tab.url) return

        val notice =
            RedirectNotice(
                originalUrl = original,
                redirectedUrl = result.redirectedUrl,
                serviceName = alternatives.routeName,
                frontendName = alternatives.frontendName,
                routeId = result.routeId,
                frontendId = result.frontendId,
                instance = result.instance,
            )
        pendingRedirectTargets.getOrPut(tab.id) { mutableSetOf() }.add(result.redirectedUrl)
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { t ->
                        if (t.id == tab.id) {
                            t.copy(url = result.redirectedUrl, lastRedirect = notice)
                        } else {
                            t
                        }
                    },
            )
        }
        webViewPool[tab.id]?.loadUrl(result.redirectedUrl)
    }

    /**
     * Instances the active tab's original link could be served by, or null when
     * the tab has no redirectable original link (routing off, disabled route,
     * unknown route, or a frontend without instances).
     */
    @Suppress("ReturnCount")
    private fun computeInstanceAlternatives(): InstanceAlternatives? {
        val manifest = cachedManifest ?: return null
        if (!cachedSettings.routingEnabled) return null
        val tab = _uiState.value.activeTab ?: return null
        val original = tab.originalUrl ?: return null
        val route = RouteMatcher(manifest).match(BrowserTab.hostOf(original)) ?: return null
        if (route.id in cachedSettings.disabledRoutes) return null
        val frontendId = cachedSettings.selectedFrontends[route.id] ?: route.frontends.first().id
        val frontend = route.frontends.find { it.id == frontendId } ?: route.frontends.first()
        if (!Strategy.requiresInstances(frontend.strategy)) return null

        val currentInstance =
            tab.lastRedirect
                ?.takeIf { it.routeId == route.id && it.frontendId == frontend.id }
                ?.instance
                ?: frontend.instances.firstOrNull { instance ->
                    BrowserTab.hostOf(tab.url) == BrowserTab.hostOf(instance)
                }
        return InstanceAlternatives(
            routeId = route.id,
            routeName = route.name,
            frontendId = frontend.id,
            frontendName = frontend.name,
            instances = frontend.instances,
            currentIndex = currentInstance?.let { frontend.instances.indexOf(it) } ?: -1,
        )
    }

    /** Resolves route/frontend display names once the manifest is available. */
    private fun routeNames(
        manifest: Manifest?,
        routeId: String?,
        frontendId: String?,
    ): Pair<String, String> {
        val route = routeId?.let { id -> manifest?.routes?.find { it.id == id } }
        val frontend = frontendId?.let { fid -> route?.frontends?.find { it.id == fid } }
        return Pair(route?.name ?: routeId.orEmpty(), frontend?.name ?: frontendId.orEmpty())
    }

    /**
     * Restores the persisted open-tab session on cold start so a process
     * death does not drop every open tab. Only URLs and redirect metadata
     * are restored; each WebView reloads its URL when first shown. Names in
     * redirect notices are filled in later by [enrichRedirectNotices] once
     * the manifest is available.
     */
    private suspend fun restoreSession() {
        val session = browserDataRepository.loadSession() ?: return
        val restoredTabs =
            session.tabs
                .asSequence()
                .filter { it.url.startsWith("http", ignoreCase = true) }
                .take(MAX_RESTORED_TABS)
                .map { persisted ->
                    val notice =
                        persisted.routeId?.let { rid ->
                            val (serviceName, frontendName) = routeNames(cachedManifest, rid, persisted.frontendId)
                            RedirectNotice(
                                originalUrl = persisted.originalUrl ?: persisted.url,
                                redirectedUrl = persisted.url,
                                serviceName = serviceName,
                                frontendName = frontendName,
                                routeId = rid,
                                frontendId = persisted.frontendId,
                                instance = persisted.instance,
                            )
                        }
                    BrowserTab(
                        url = persisted.url,
                        title = BrowserTab.hostOf(persisted.url),
                        lastRedirect = notice,
                        originalUrl = persisted.originalUrl?.takeIf { notice != null },
                    )
                }.toList()
        if (restoredTabs.isEmpty()) return
        val restoredActiveId = restoredTabs[session.activeIndex.coerceIn(0, restoredTabs.lastIndex)].id
        _uiState.update { current ->
            // Loading the session suspends on DataStore, so on a cold start the
            // launching intent (an external link) has usually already opened its
            // tab by now. Keep those tabs — and their focus — instead of
            // overwriting them with the restored session; only the placeholder
            // start tab is replaced.
            val arrivedTabs = current.tabs.filterNot { it.id == initialTab.id }
            val keptRestored =
                restoredTabs.filterNot { restored ->
                    arrivedTabs.any { it.url == restored.url || it.originalUrl == restored.url }
                }
            val activeId =
                current.activeTabId.takeIf { id -> arrivedTabs.any { it.id == id } }
                    ?: restoredActiveId.takeIf { id -> keptRestored.any { it.id == id } }
                    ?: (keptRestored + arrivedTabs).first().id
            current.copy(tabs = keptRestored + arrivedTabs, activeTabId = activeId)
        }
        // The banner derives from the active tab's notice; a session restored
        // from a previous run must not announce its old redirects again, so
        // they start out dismissed. Notices are copies — timestamps survive
        // the later name enrichment.
        val restoredNoticeTimestamps = restoredTabs.mapNotNull { it.lastRedirect?.timestamp }
        _uiState.update { current ->
            current.copy(dismissedNoticeTimestamps = current.dismissedNoticeTimestamps + restoredNoticeTimestamps)
        }
        _uiState.value.tabs.forEach { tab ->
            if (tab.lastRedirect != null && restoredTabs.any { it.id == tab.id }) {
                // Frontend origins must not be re-resolved on load, exactly
                // like freshly redirected tabs.
                pendingRedirectTargets.getOrPut(tab.id) { mutableSetOf() }.add(tab.url)
            }
        }
    }

    /**
     * Persists the open-tab session, debounced so bursts of updates (page
     * loads, tab churn) coalesce into one DataStore write. Destructive
     * operations (closing tabs) save immediately instead.
     */
    private fun scheduleSessionPersist(immediate: Boolean = false) {
        sessionPersistJob?.cancel()
        sessionPersistJob =
            viewModelScope.launch {
                if (!immediate) {
                    delay(SESSION_SAVE_DEBOUNCE_MILLIS)
                }
                persistSession()
            }
    }

    private suspend fun persistSession() {
        val state = _uiState.value
        var activeIndex = 0
        val tabs =
            buildList {
                state.tabs.forEachIndexed { index, tab ->
                    val url = tab.url
                    if (!url.startsWith("http", ignoreCase = true)) return@forEachIndexed
                    if (tab.id == state.activeTabId) activeIndex = size
                    add(
                        PersistedTab(
                            url = url,
                            originalUrl = tab.originalUrl,
                            routeId = tab.lastRedirect?.routeId,
                            frontendId = tab.lastRedirect?.frontendId,
                            instance = tab.lastRedirect?.instance,
                        ),
                    )
                }
            }
        browserDataRepository.saveSession(PersistedSession(tabs = tabs, activeIndex = activeIndex))
    }

    /**
     * Tabs created from an intent may predate the manifest being cached (cold
     * start); fill their redirect notices with proper display names once it is.
     * The banner derives from the active tab's notice, so tabs are all there
     * is to enrich.
     */
    private fun enrichRedirectNotices() {
        val manifest = cachedManifest ?: return
        _uiState.update { current ->
            val tabs =
                current.tabs.map { tab ->
                    val notice = tab.lastRedirect
                    if (notice?.routeId == null) {
                        tab
                    } else {
                        val (serviceName, frontendName) = routeNames(manifest, notice.routeId, notice.frontendId)
                        tab.copy(lastRedirect = notice.copy(serviceName = serviceName, frontendName = frontendName))
                    }
                }
            current.copy(tabs = tabs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(
        tabId: String,
        context: Context,
        darkPages: Boolean,
    ): WebView {
        val existing = webViewPool[tabId]
        if (existing != null && webViewDarkModes[tabId] == darkPages) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        // A WebView's page theme is fixed at creation; carry its back/forward
        // history over to the replacement built with the new theme.
        val savedState =
            existing?.let { webView ->
                Bundle().also { webView.saveState(it) }
            }
        if (existing != null) destroyWebView(tabId)

        val tab = _uiState.value.tabs.find { it.id == tabId }
        val newWebView = buildWebView(tabId, context, darkPages, tab?.isDesktopMode == true)

        webViewPool[tabId] = newWebView
        webViewDarkModes[tabId] = darkPages

        val restored = savedState?.let { newWebView.restoreState(it) } != null
        if (!restored) {
            loadInitialPage(newWebView, tabId, tab, isReplacement = existing != null)
        }
        return newWebView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        tabId: String,
        context: Context,
        darkPages: Boolean,
        isDesktop: Boolean,
    ): WebView =
        WebView(WebViewTheming.themedContext(context, darkPages)).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                // Pinned privacy posture: explicit rather than relying on
                // WebView defaults that may vary across versions.
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }
            // Third-party cookies are accepted by default; a privacy proxy
            // should not ship that default.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            WebViewTheming.applyBackground(this, darkPages)
            WebViewTheming.applyAutoDarken(this, darkPages, _uiState.value.autoDarken)
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

    private fun loadInitialPage(
        webView: WebView,
        tabId: String,
        tab: BrowserTab?,
        isReplacement: Boolean,
    ) {
        val target = tab?.url ?: BrowserTab.DEFAULT_HOME_URL
        // A tab carrying redirect metadata is already sitting on a resolved
        // destination: load it as-is instead of routing it a second time.
        val alreadyRouted = tab?.lastRedirect != null
        if (isReplacement || alreadyRouted) {
            if (alreadyRouted) {
                pendingRedirectTargets.getOrPut(tabId) { mutableSetOf() }.add(target)
            }
            webView.loadUrl(target)
            return
        }
        // Cold-start window: manifest and settings are still streaming in, so
        // routing now would either skip a redirect or ignore the user's
        // disabled settings. Hold the load until they have landed.
        viewModelScope.launch {
            initialStateLoaded.await()
            if (webViewPool[tabId] !== webView) return@launch // tab closed or re-keyed meanwhile
            val currentUrl = _uiState.value.tabs.find { it.id == tabId }?.url ?: return@launch
            val resolved = resolveRedirection(currentUrl, tabId) ?: currentUrl
            if (webViewPool[tabId] === webView) {
                webView.loadUrl(resolved)
            }
        }
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
        // On API < 29 the shared Downloads directory needs a storage
        // permission this app does not request; surface the private-dir
        // fallback instead of letting the file "disappear" into app storage.
        val usingPrivateFallback =
            try {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                false
            } catch (_: SecurityException) {
                request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)
                true
            }
        try {
            app.getSystemService(DownloadManager::class.java).enqueue(request)
            val messageRes =
                if (usingPrivateFallback) {
                    R.string.browser_download_private_fallback
                } else {
                    R.string.browser_download_started
                }
            Toast.makeText(app, messageRes, Toast.LENGTH_SHORT).show()
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
                desktopUserAgent(WebSettings.getDefaultUserAgent(webView.context))
            } else {
                WebSettings.getDefaultUserAgent(webView.context)
            }
        webView.settings.userAgentString = userAgent
        webView.settings.useWideViewPort = isDesktopMode
        webView.settings.loadWithOverviewMode = isDesktopMode
    }

    /**
     * Derives the desktop UA from the WebView's own default so the Chrome
     * version and platform tokens age with the installed WebView instead of
     * freezing at a hardcoded string. Mirrors what desktop-mode browsers do:
     * swap the mobile platform descriptor for a desktop one and drop the
     * "Mobile" token. [BrowserTab.DESKTOP_USER_AGENT] remains as a fallback
     * when the default UA has an unexpected shape.
     */
    private fun desktopUserAgent(defaultUserAgent: String): String {
        val desktop =
            defaultUserAgent
                .replace(Regex("\\((Linux; )?Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile Safari", " Safari")
        return if (desktop == defaultUserAgent) BrowserTab.DESKTOP_USER_AGENT else desktop
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
        scheduleSessionPersist()
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

    private fun updateTabFavicon(
        tabId: String,
        favicon: Bitmap?,
    ) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId) tab.copy(favicon = favicon) else tab
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
                val isPendingTarget = pendingRedirectTargets[tabId]?.contains(urlString) == true
                val redirected = if (isPendingTarget) null else resolveRedirection(urlString, tabId)
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
                if (pendingRedirectTargets[tabId]?.contains(url) == true) {
                    // This is the resolved destination of a redirect we already
                    // performed: keep the tab's original-link metadata intact.
                    updateTabUrl(tabId, url)
                    updateTabFavicon(tabId, favicon)
                    updateTabLoading(tabId, isLoading = true, progress = 10)
                    if (tabId == _uiState.value.activeTabId) {
                        _uiState.update { it.copy(findMatchCount = 0, findActiveMatchIndex = 0) }
                    }
                    return
                }
                val redirected = resolveRedirection(url, tabId)
                if (redirected != null && redirected != url) {
                    view?.stopLoading()
                    view?.loadUrl(redirected)
                    return
                }
                updateTabUrl(tabId, url)
                updateTabFavicon(tabId, favicon)
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

        override fun onReceivedIcon(
            view: WebView?,
            icon: Bitmap?,
        ) {
            super.onReceivedIcon(view, icon)
            if (icon != null) {
                updateTabFavicon(tabId, icon)
            }
        }
    }

    private companion object {
        const val SESSION_SAVE_DEBOUNCE_MILLIS = 500L
        const val MAX_RESTORED_TABS = 50
        const val LATENCY_TIMEOUT_SECONDS = 5L
        const val LATENCY_PROBE_CONCURRENCY = 16
        const val LATENCY_FAILED_MILLIS = -1L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
