package dev.libredirect.mobile.ui.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.R
import dev.libredirect.mobile.blocker.BlocklistStore
import dev.libredirect.mobile.blocker.ContentBlocker
import dev.libredirect.mobile.browser.InstanceFailover
import dev.libredirect.mobile.browser.InstanceProbe
import dev.libredirect.mobile.browser.ServiceShortcutResolver
import dev.libredirect.mobile.browser.ShortLinkResolver
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.routing.ExceptionMatcher
import dev.libredirect.mobile.core.routing.InstanceHealth
import dev.libredirect.mobile.core.routing.ReverseRouter
import dev.libredirect.mobile.core.routing.RouteMatcher
import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.routing.UrlRouter
import dev.libredirect.mobile.core.url.CustomTrackingScope
import dev.libredirect.mobile.core.url.ShortLinkPolicy
import dev.libredirect.mobile.core.url.TrackingParams
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.core.url.UrlUnwrapper
import dev.libredirect.mobile.downloads.DownloadEvent
import dev.libredirect.mobile.downloads.DownloadFailureReason
import dev.libredirect.mobile.downloads.DownloadItem
import dev.libredirect.mobile.downloads.DownloadSourceKind
import dev.libredirect.mobile.downloads.DownloadStorage
import dev.libredirect.mobile.downloads.OpenFileResult
import dev.libredirect.mobile.downloads.PendingDownloadRequest
import dev.libredirect.mobile.downloads.ensureFileExtension
import dev.libredirect.mobile.downloads.fileExtensionOf
import dev.libredirect.mobile.downloads.guessMimeType
import dev.libredirect.mobile.downloads.isGenericMimeType
import dev.libredirect.mobile.downloads.isPotentiallyDangerousFile
import dev.libredirect.mobile.downloads.parseDataUri
import dev.libredirect.mobile.downloads.prepareDownloadUrl
import dev.libredirect.mobile.downloads.resolveInitialHttpDownloadMetadata
import dev.libredirect.mobile.downloads.sanitizeFileName
import dev.libredirect.mobile.media.MediaPlaybackBus
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
import dev.libredirect.mobile.settings.SiteSetting
import dev.libredirect.mobile.settings.TabAutoClose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

/**
 * A WebView file-chooser request (file upload fields). [intent] is what the
 * Chrome client's [WebChromeClient.FileChooserParams.createIntent] built; the
 * id lets the UI distinguish a new request from a stale one.
 */
data class FileChooserRequest(
    val id: Long,
    val intent: Intent,
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
    /** Hide the toolbars while the page is scrolled down (4.5 setting). */
    val autoHideToolbar: Boolean = false,
    val pageTheme: PageTheme = PageTheme.DEFAULT,
    val autoDarken: Boolean = true,
    /** Page text scaling in percent (100 = default), editable from the browser menu. */
    val textZoom: Int = 100,
    /** A page's `<video>`/fullscreen element is rendering its own view; the browser UI is hidden. */
    val isFullscreenVideo: Boolean = false,
    val fullscreenView: View? = null,
    /** Width/height of the fullscreen view, for the PiP aspect ratio (4.1). */
    val fullscreenAspectRatio: Float? = null,
    /** The activity is currently in picture-in-picture (toolbars hidden anyway). */
    val isInPipMode: Boolean = false,
    /** Long-pressed link/image target offered to the context menu; null hides the dialog. */
    val linkContextUrl: String? = null,
    val linkContextIsImage: Boolean = false,
    /** Pending `onShowFileChooser` request; null when none. */
    val fileChooserRequest: FileChooserRequest? = null,
    /** Live in-app download list, ordered newest-first. */
    val downloads: List<DownloadItem> = emptyList(),
    val isDownloadsSheetVisible: Boolean = false,
    /** Download waiting for user confirmation/renaming in the pre-download sheet. */
    val pendingDownloadRequest: PendingDownloadRequest? = null,
    val askBeforeDownload: Boolean = true,
    val downloadWifiOnly: Boolean = false,
) {
    val activeDownloadCount: Int
        get() = downloads.count { it.status.isActive }
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
    private val downloadRepository = app.downloadRepository
    private val downloadStorage = DownloadStorage(app)
    private val blocklistStore = BlocklistStore(app)
    private val instanceProbe = InstanceProbe()
    private val shortLinkResolver = ShortLinkResolver()

    val downloadEvents: SharedFlow<DownloadEvent> = downloadRepository.events

    private data class BlobSession(
        val requestId: String,
        val tabId: String,
        val nonce: String,
        val originalUrl: String,
        val sourceHost: String,
        val suggestedName: String,
        val mimeHint: String?,
        val userAgent: String,
        val isIncognito: Boolean,
        var downloadId: String? = null,
    )

    private val blobSessions = ConcurrentHashMap<String, BlobSession>()

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
     * In-memory snapshot of instance latencies + failure cooldowns (2.1),
     * kept current so the synchronous WebView navigation path can route with
     * health data without suspending.
     */
    @Volatile
    private var cachedHealth: Map<String, InstanceHealth> = emptyMap()

    /**
     * Completed once the manifest and the first settings value have landed on
     * a cold start. Until then [resolveRedirection] would route against
     * defaults (or skip routing on a null manifest), so initial page loads
     * wait for it instead of racing the first seconds of startup.
     */
    private val initialStateLoaded = CompletableDeferred<Unit>()

    private var sessionPersistJob: Job? = null
    private var pendingMetadataProbeJob: Job? = null

    private val webViewPool = mutableMapOf<String, WebView>()

    /** Last raw scroll offset per tab, kept outside the state flow (see onTabScroll). */
    private val lastScrollY = mutableMapOf<String, Int>()

    /** Whether each pooled WebView was created with a dark theme; see [WebViewTheming]. */
    private val webViewDarkModes = mutableMapOf<String, Boolean>()

    /** Last auto-darken value pushed to the WebViews, so unrelated settings changes don't reload pages. */
    private var appliedAutoDarken: Boolean? = null

    /** Last text-zoom value pushed to the WebViews, so unrelated settings changes don't touch them. */
    private var appliedTextZoom: Int? = null

    /** Monotonic "last used" marker per pooled WebView, driving pool trimming (see [trimWebViewPool]). */
    private val webViewTouchedAt = mutableMapOf<String, Long>()

    /**
     * Back/forward history of WebViews evicted from the pool by trimming, so a
     * trimmed tab keeps its navigation history when the user comes back to it.
     */
    private val webViewSavedStates = mutableMapOf<String, Bundle>()

    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var fileChooserRequestId = 0L

    init {
        viewModelScope.launch {
            restoreSession()
            cachedManifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
            // Take the first settings value synchronously so no navigation can
            // resolve against defaults while DataStore is still reading.
            cachedSettings = settingsRepository.settings.first()
            refreshHealthSnapshot()
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
                        textZoom = settings.browserTextZoom,
                        autoHideToolbar = settings.autoHideToolbar,
                        askBeforeDownload = settings.askBeforeDownload,
                        downloadWifiOnly = settings.downloadWifiOnly,
                    )
                }
                applyAutoDarkenToAll(settings.browserAutoDarken)
                applyTextZoomToAll(settings.browserTextZoom)
            }
        }
        viewModelScope.launch {
            downloadRepository.downloads.collect { items ->
                _uiState.update { it.copy(downloads = items) }
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
        MediaPlaybackBus.commands
            .onEach { command -> onMediaCommand(command) }
            .launchIn(viewModelScope)
    }

    /** Reloads the in-memory instance-health snapshot from local storage (2.1). */
    private fun refreshHealthSnapshot() {
        viewModelScope.launch {
            val latencies = browserDataRepository.cachedLatencies()
            val failures = browserDataRepository.instanceFailures()
            cachedHealth =
                (latencies.keys + failures.keys).associateWith { host ->
                    InstanceHealth(latencyMillis = latencies[host], lastFailedAtMillis = failures[host])
                }
        }
    }

    /** Notification actions (4.6): run play/pause as JS into the active page. */
    private fun onMediaCommand(command: MediaPlaybackBus.Command) {
        val webView = webViewPool[_uiState.value.activeTabId] ?: return
        when (command) {
            MediaPlaybackBus.Command.PLAY_PAUSE ->
                mediaToggleJs()?.let { js -> webView.evaluateJavascript(js, null) }
            MediaPlaybackBus.Command.STOP ->
                MediaPlaybackBus.reportPlaying(false)
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
        captureActiveTabThumbnail()
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
        trimWebViewPool()
    }

    /**
     * Private tab: never written to history, session persistence or bookmarks;
     * when the WebView Profile API is available its cookies and site storage
     * live in an ephemeral profile deleted with the last incognito tab.
     */
    fun openIncognitoTab() {
        val newTab =
            BrowserTab(
                id = UUID.randomUUID().toString(),
                url = BrowserTab.DEFAULT_HOME_URL,
                isIncognito = true,
            )
        _uiState.update { current ->
            current
                .copy(
                    tabs = current.tabs + newTab,
                    activeTabId = newTab.id,
                ).withOverlaysClosed()
        }
        requestBrowserFocus()
        trimWebViewPool()
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
        captureActiveTabThumbnail()
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
        trimWebViewPool()
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
            isDownloadsSheetVisible = false,
            isSearchEngineDialogVisible = false,
            isFindInPageVisible = false,
            isInstancePickerVisible = false,
            isClearDataConfirmVisible = false,
        )

    fun openUrlInNewOrActiveTab(url: String) {
        captureActiveTabThumbnail()
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
        trimWebViewPool()
    }

    fun closeTab(tabId: String) {
        if (_uiState.value.isFullscreenVideo) exitFullscreenVideo()
        destroyWebView(tabId, keepHistoryForReuse = false)
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
        deleteIncognitoProfileIfUnused()
        scheduleSessionPersist(immediate = true)
    }

    fun closeAllTabs() {
        if (_uiState.value.isFullscreenVideo) exitFullscreenVideo()
        webViewPool.keys.toList().forEach { destroyWebView(it, keepHistoryForReuse = false) }
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
        deleteIncognitoProfileIfUnused()
        scheduleSessionPersist(immediate = true)
    }

    /**
     * Destroys a tab's WebView. With [keepHistoryForReuse] (pool trimming) the
     * back/forward history is stashed so the tab can restore it when its
     * WebView is rebuilt; otherwise any stashed state for the tab is dropped
     * too, since the tab itself is going away.
     */
    private fun destroyWebView(
        tabId: String,
        keepHistoryForReuse: Boolean,
    ) {
        lastScrollY.remove(tabId)
        webViewDarkModes.remove(tabId)
        webViewTouchedAt.remove(tabId)
        val webView = webViewPool.remove(tabId)
        if (webView != null && keepHistoryForReuse) {
            webViewSavedStates[tabId] = Bundle().also { webView.saveState(it) }
        } else {
            webViewSavedStates.remove(tabId)
        }
        webView?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    /**
     * Keeps at most [MAX_ALIVE_WEB_VIEWS] WebViews around: background tabs
     * beyond that have their WebView destroyed (history stashed) and are
     * rebuilt on demand when selected. Least-recently-used inactive tabs are
     * evicted first; the active tab is never a victim.
     */
    private fun trimWebViewPool() {
        while (webViewPool.size > MAX_ALIVE_WEB_VIEWS) {
            val victim =
                WebViewPoolPolicy.evictionCandidate(
                    pooledTabIds = webViewPool.keys,
                    touchedAt = webViewTouchedAt,
                    activeTabId = _uiState.value.activeTabId,
                    maxAliveWebViews = MAX_ALIVE_WEB_VIEWS,
                ) ?: break
            destroyWebView(victim, keepHistoryForReuse = true)
        }
        webViewTouchedAt.keys.toList().forEach { tabId ->
            if (tabId !in webViewPool) webViewTouchedAt.remove(tabId)
        }
    }

    private fun touchWebView(tabId: String) {
        webViewTouchedAt[tabId] = System.currentTimeMillis()
    }

    /**
     * Captures a scaled screenshot of the given tab's WebView as its switcher
     * thumbnail. A WebView is attached and laid out only while its tab is
     * active, so this runs in the moments before the active tab changes.
     */
    @Suppress("ReturnCount")
    private fun captureActiveTabThumbnail() {
        val tabId = _uiState.value.activeTabId
        // An incognito tab's page never appears anywhere, not even as a
        // memory-only thumbnail in the switcher.
        if (_uiState.value.tabs.find { it.id == tabId }?.isIncognito == true) return
        val webView = webViewPool[tabId] ?: return
        val width = webView.width
        val height = webView.height
        if (width == 0 || height == 0) return
        val scale = THUMBNAIL_WIDTH_PX.toFloat() / width
        val bitmap =
            Bitmap.createBitmap(
                THUMBNAIL_WIDTH_PX,
                (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        webView.draw(canvas)
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId) tab.copy(thumbnail = bitmap) else tab
                    },
            )
        }
    }

    fun selectTab(tabId: String) {
        captureActiveTabThumbnail()
        webViewPool[_uiState.value.activeTabId]?.clearMatches()
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        // lastActiveAt drives the tab switcher's recent sort (4.5).
                        if (tab.id == tabId) tab.copy(lastActiveAt = System.currentTimeMillis()) else tab
                    },
                activeTabId = tabId,
                isTabsSheetVisible = false,
                isFindInPageVisible = false,
                findMatchCount = 0,
                findActiveMatchIndex = 0,
            )
        }
        touchWebView(tabId)
        trimWebViewPool()
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
                // Kept after close, not nulled: the "try another instance"
                // picker renders from it once the menu is gone (and a later
                // pick or latency probe reads it too). Reopening the menu
                // recomputes it against the active tab.
                instanceAlternatives =
                    if (visible) {
                        computeInstanceAlternatives()
                    } else {
                        it.instanceAlternatives
                    },
            )
        }
    }

    fun setInstancePickerVisible(visible: Boolean) {
        _uiState.update {
            if (visible) {
                it.copy(
                    isInstancePickerVisible = true,
                    // Recompute on every open: the menu may have stayed open
                    // while a previous pick already re-routed the tab, so the
                    // "current instance" marker must reflect the latest
                    // redirect, not the menu-open snapshot.
                    instanceAlternatives = computeInstanceAlternatives(),
                    isMeasuringInstances = false,
                )
            } else {
                it.copy(isInstancePickerVisible = false)
            }
        }
        if (visible) preloadCachedLatencies()
    }

    /** Opens the instance picker only when the active tab actually has alternatives. */
    fun openInstancePickerIfAvailable() {
        if (computeInstanceAlternatives() != null) setInstancePickerVisible(true)
    }

    /** Seeds the picker with latencies measured in an earlier session, so the ranking survives restarts. */
    private fun preloadCachedLatencies() {
        val instances = _uiState.value.instanceAlternatives?.instances ?: return
        viewModelScope.launch {
            val relevant = filterLatencies(browserDataRepository.cachedLatencies(), instances)
            if (relevant.isEmpty()) return@launch
            _uiState.update { current ->
                current.copy(instanceLatencies = relevant)
            }
        }
    }

    /**
     * User-initiated probe of the current frontend's instances: a bounded
     * concurrency pool of HEAD requests measures time-to-first-byte so the
     * picker can rank instances and suggest the fastest one to pin. Runs
     * only on request — never in the background. Results are cached so a
     * later session opens the picker with the ranking already in place.
     */
    fun measureInstanceLatency() {
        val alternatives = _uiState.value.instanceAlternatives ?: return
        if (_uiState.value.isMeasuringInstances) return
        _uiState.update { it.copy(isMeasuringInstances = true, instanceLatencies = emptyMap()) }
        viewModelScope.launch {
            val results = instanceProbe.measure(alternatives.instances)
            _uiState.update { it.copy(instanceLatencies = results, isMeasuringInstances = false) }
            browserDataRepository.saveLatencies(results)
            refreshHealthSnapshot()
        }
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

    fun setBrowserTextZoom(percent: Int) {
        viewModelScope.launch {
            settingsRepository.setBrowserTextZoom(percent)
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

    /** Text scaling takes effect on live WebViews immediately; no reload needed. */
    private fun applyTextZoomToAll(percent: Int) {
        if (appliedTextZoom == percent) return
        appliedTextZoom = percent
        webViewPool.values.forEach { webView ->
            webView.settings.textZoom = percent
        }
    }

    /**
     * Fullscreen video ([WebChromeClient.onShowCustomView]): the page's own
     * view takes over the whole screen until the page or the user ends it.
     */
    private fun enterFullscreenVideo(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
    ) {
        if (_uiState.value.isFullscreenVideo) {
            callback.onCustomViewHidden()
            return
        }
        customViewCallback = callback
        // Capture the ratio now: once laid out the view is what PiP mirrors (4.1).
        val aspect = if (view.width > 0 && view.height > 0) view.width.toFloat() / view.height else null
        _uiState.update { it.copy(isFullscreenVideo = true, fullscreenView = view, fullscreenAspectRatio = aspect) }
    }

    /** User-initiated exit (back gesture/button): ends the fullscreen view via its callback. */
    fun exitFullscreenVideo() {
        if (!_uiState.value.isFullscreenVideo) return
        val callback = customViewCallback
        customViewCallback = null
        if (callback != null) {
            // The page's chrome client answers with onHideCustomView, which clears the state.
            callback.onCustomViewHidden()
        }
        if (_uiState.value.isFullscreenVideo) clearFullscreenVideoState()
    }

    /** Page/system-initiated exit: tear the custom view down and restore the browser UI. */
    private fun clearFullscreenVideoState() {
        val view = _uiState.value.fullscreenView
        _uiState.update { it.copy(isFullscreenVideo = false, fullscreenView = null, fullscreenAspectRatio = null) }
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    // --- 4.1 picture-in-picture ---

    /** True when leaving the app now should drop into PiP (fullscreen video playing). */
    fun shouldEnterPictureInPicture(): Boolean = _uiState.value.isFullscreenVideo

    /** Aspect ratio of the current fullscreen view, or null when unknown. */
    fun fullscreenAspectRatio(): Float? =
        _uiState.value.fullscreenAspectRatio
            ?: _uiState.value.fullscreenView
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { view -> view.width.toFloat() / view.height }

    /** Aspect-ratio hint captured when fullscreen video starts. */
    fun onFullscreenVideoStarted(aspectRatio: Float?) {
        if (aspectRatio != null && aspectRatio > 0f) {
            _uiState.update { it.copy(fullscreenAspectRatio = aspectRatio) }
        }
    }

    fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
        _uiState.update { it.copy(isInPipMode = isInPipMode) }
    }

    /** Shows the long-press context menu for a link/image target picked from [WebView.hitTestResult]. */
    private fun showLinkContextMenu(
        url: String,
        isImage: Boolean,
    ) {
        _uiState.update { it.copy(linkContextUrl = url, linkContextIsImage = isImage) }
    }

    fun dismissLinkContextMenu() {
        _uiState.update { it.copy(linkContextUrl = null, linkContextIsImage = false) }
    }

    fun downloadFromLinkContext(url: String) {
        dismissLinkContextMenu()
        val tabId = _uiState.value.activeTabId
        val userAgent =
            webViewPool[tabId]?.settings?.userAgentString
                ?: WebSettings.getDefaultUserAgent(app)
        handleWebViewDownload(
            tabId = tabId,
            url = url,
            userAgent = userAgent,
            contentDisposition = null,
            mimeType = null,
            contentLength = -1L,
        )
    }

    fun openLinkInNewTab(url: String) {
        dismissLinkContextMenu()
        openNewTab(url)
    }

    fun copyLinkToClipboard(url: String) {
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("link", url))
        dismissLinkContextMenu()
        Toast.makeText(app, R.string.browser_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun requestFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean =
        try {
            pendingFileChooserCallback?.onReceiveValue(null)
            pendingFileChooserCallback = callback
            fileChooserRequestId += 1
            _uiState.update {
                it.copy(
                    fileChooserRequest = FileChooserRequest(fileChooserRequestId, params.createIntent()),
                )
            }
            true
        } catch (_: Exception) {
            callback.onReceiveValue(null)
            false
        }

    /** The file picker returned (result may be a cancellation): the WebView callback must always fire exactly once. */
    fun onFileChooserResult(
        resultCode: Int,
        data: Intent?,
    ) {
        val callback = pendingFileChooserCallback ?: return
        pendingFileChooserCallback = null
        _uiState.update { it.copy(fileChooserRequest = null) }
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
    }

    /**
     * Launcher-shortcut entry (C3): opens the service's chosen frontend
     * instance in a new tab. Falls back to the first instance when the user
     * never made an explicit selection; silently ignored when the route or an
     * instance cannot be determined (the manifest may have changed).
     */
    fun openServiceShortcut(routeId: String?) {
        if (routeId.isNullOrBlank()) return
        // A home-screen shortcut can arrive on a cold start, before the
        // manifest and settings have landed; resolve against them once ready.
        viewModelScope.launch {
            initialStateLoaded.await()
            resolveServiceShortcut(routeId)
        }
    }

    @Suppress("ReturnCount")
    private fun resolveServiceShortcut(routeId: String?) {
        if (routeId.isNullOrBlank()) return
        val route = cachedManifest?.routes?.find { it.id == routeId } ?: return
        val instance =
            ServiceShortcutResolver.resolveInstanceUrl(
                route = route,
                selectedFrontends = cachedSettings.selectedFrontends,
                instanceSelections = cachedSettings.instanceSelections,
            ) ?: return
        openNewTab(instance)
    }

    /**
     * Deletes the ephemeral incognito profile once no incognito tab exists, so
     * its cookies and site storage die with the session (no-op when the
     * WebView Profile API is unavailable or a WebView still uses the profile).
     */
    private fun deleteIncognitoProfileIfUnused(retryAllowed: Boolean = true) {
        if (cachedSettings.clearIncognitoDownloads && _uiState.value.tabs.none { it.isIncognito }) {
            downloadRepository.clearIncognitoHistory()
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        if (_uiState.value.tabs.any { it.isIncognito }) return
        try {
            ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE_NAME)
        } catch (_: IllegalStateException) {
            // WebView.destroy() is asynchronous: right after the last incognito
            // tab closes the profile can still be reported as in use, and
            // deleteProfile throws instead of returning false. Retry once
            // shortly after the renderer has released it.
            if (retryAllowed) {
                viewModelScope.launch {
                    delay(PROFILE_DELETE_RETRY_MILLIS)
                    deleteIncognitoProfileIfUnused(retryAllowed = false)
                }
            }
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
        // The incognito profile owns separate storage; wipe it too while it exists.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore
                .getInstance()
                .getProfile(INCOGNITO_PROFILE_NAME)
                ?.cookieManager
                ?.removeAllCookies(null)
        }
        webViewPool.values.forEach { webView ->
            webView.clearCache(true)
        }
        _uiState.update { it.copy(isClearDataConfirmVisible = false) }
    }

    fun setClearDataConfirmVisible(visible: Boolean) {
        _uiState.update { it.copy(isClearDataConfirmVisible = visible) }
    }

    @Suppress("ReturnCount")
    fun toggleBookmarkActivePage() {
        val active = _uiState.value.activeTab ?: return
        if (active.isIncognito) return
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

    @Suppress("ReturnCount", "LongMethod")
    private fun resolveRedirection(
        url: String,
        tabId: String,
    ): String? {
        val settings = cachedSettings
        val manifest = cachedManifest
        // Paused routing (2.6) behaves like the master switch off here too.
        if (!settings.routingEnabled || settings.isRoutingPaused() || manifest == null) return null

        // 3.1: unwrap wrappers/AMP locally before routing; an excepted URL is
        // left byte-for-byte alone.
        var workingUrl = url
        if (settings.unwrapRedirectWrappersEnabled && !isExceptionMatch(url)) {
            val unwrapped = UrlUnwrapper.unwrapAll(workingUrl)
            if (unwrapped.url != workingUrl) {
                workingUrl = unwrapped.url
                addPrivacyStats(PrivacyStats(unwrappedWrappers = unwrapped.count.toLong()))
            }
        }

        // 3.3: strictly opt-in short-link resolution needs the network, so it
        // runs async and re-enters routing with the resolved target; the
        // original link loads meanwhile (fail-open).
        if (settings.shortLinkResolveEnabled && !isExceptionMatch(workingUrl) && isAllowedShortener(workingUrl)) {
            viewModelScope.launch {
                val resolved = shortLinkResolver.resolve(workingUrl)
                if (resolved.changed && resolved.url != workingUrl) {
                    addPrivacyStats(PrivacyStats(resolvedShortLinks = 1))
                    routeAndApply(resolved.url, tabId, originalForRecord = workingUrl)
                }
            }
        }
        return routeAndApply(workingUrl, tabId, originalForRecord = null)
    }

    /**
     * Runs the actual routing decision for a (preprocessed) URL and applies
     * the redirect to the tab. Returns the redirected URL, or null when the
     * link passes through.
     */
    @Suppress("ReturnCount") // guard chain: early-outs precede the redirect apply
    private fun routeAndApply(
        url: String,
        tabId: String,
        originalForRecord: String?,
    ): String? {
        val manifest = cachedManifest ?: return null
        val settings = cachedSettings
        val context =
            settings
                .routingContext()
                .copy(instanceHealth = cachedHealth, nowMillis = System.currentTimeMillis())
        val result = UrlRouter(manifest).resolve(url, context)
        if (result !is RoutingResult.Redirect) return null

        // Local, per-route usage counter (A6) + privacy counters (3.4).
        val strippedCount = strippedParamCount(url)
        viewModelScope.launch {
            browserDataRepository.recordRedirect(result.routeId)
            addPrivacyStats(
                PrivacyStats(
                    totalRedirects = 1,
                    strippedTrackingParams = strippedCount.toLong(),
                ),
            )
        }

        val route = manifest.routes.find { it.id == result.routeId }
        val serviceName = route?.name ?: result.routeId
        val frontendName = route?.frontends?.find { it.id == result.frontendId }?.name ?: result.frontendId
        val notice =
            RedirectNotice(
                originalUrl = originalForRecord ?: url,
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
                            tab.copy(
                                lastRedirect = notice,
                                originalUrl = originalForRecord ?: url,
                                failoverAttempts = 0,
                            )
                        } else {
                            tab
                        }
                    },
            )
        }
        return result.redirectedUrl
    }

    private fun isExceptionMatch(url: String): Boolean {
        val parsed = UrlParser.parse(url) ?: return false
        return ExceptionMatcher.matches(parsed, cachedSettings.exceptions)
    }

    private fun isAllowedShortener(url: String): Boolean {
        val host = UrlParser.parse(url)?.host ?: return false
        return ShortLinkPolicy.isAllowed(host)
    }

    private fun strippedParamCount(url: String): Int {
        val parsed = UrlParser.parse(url) ?: return 0
        val rules = cachedSettings.customTrackingRules()
        return TrackingParams.removedCount(
            parsed.rawQuery,
            parsed.host,
            CustomTrackingScope(global = rules.global, byHost = rules.byHost),
        )
    }

    /** Local privacy counters (3.4); the Home card shows and resets these. */
    private fun addPrivacyStats(delta: PrivacyStats) {
        viewModelScope.launch {
            browserDataRepository.addPrivacyStats(delta)
        }
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
     * Instances the given tab's original link could be served by, or null
     * when the tab has no redirectable original link (routing off, disabled
     * route, unknown route, or a frontend without instances). Defaults to
     * the active tab.
     */
    @Suppress("ReturnCount")
    private fun computeInstanceAlternatives(tab: BrowserTab? = _uiState.value.activeTab): InstanceAlternatives? {
        val manifest = cachedManifest ?: return null
        if (!cachedSettings.routingEnabled) return null
        val activeTab = tab ?: return null
        val original = activeTab.originalUrl ?: return null
        val route = RouteMatcher(manifest).match(BrowserTab.hostOf(original)) ?: return null
        if (route.id in cachedSettings.disabledRoutes) return null
        val frontendId = cachedSettings.selectedFrontends[route.id] ?: route.frontends.first().id
        val frontend = route.frontends.find { it.id == frontendId } ?: route.frontends.first()
        if (!Strategy.requiresInstances(frontend.strategy)) return null

        val currentInstance =
            activeTab.lastRedirect
                ?.takeIf { it.routeId == route.id && it.frontendId == frontend.id }
                ?.instance
                ?: frontend.instances.firstOrNull { instance ->
                    BrowserTab.hostOf(activeTab.url) == BrowserTab.hostOf(instance)
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
        val staleCutoff = staleTabCutoff()
        val restoredTabs =
            session.tabs
                .asSequence()
                .filter { it.url.startsWith("http", ignoreCase = true) }
                .filter { persisted -> isTabFreshEnough(persisted, staleCutoff) }
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
                        createdAt = persisted.createdAtEpochMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        group = persisted.group,
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
     * Cutoff below which restored tabs are dropped (4.5 auto-close); null
     * when the setting is "never". Tabs persisted before the feature has no
     * timestamp (0) and are always kept.
     */
    private fun isTabFreshEnough(
        persisted: PersistedTab,
        staleCutoff: Long?,
    ): Boolean =
        staleCutoff == null || persisted.createdAtEpochMillis <= 0 || persisted.createdAtEpochMillis >= staleCutoff

    private fun staleTabCutoff(): Long? =
        when (TabAutoClose.fromId(cachedSettings.autoCloseOldTabs)) {
            TabAutoClose.NEVER -> null
            TabAutoClose.ONE_DAY -> System.currentTimeMillis() - ONE_DAY_MILLIS
            TabAutoClose.ONE_WEEK -> System.currentTimeMillis() - ONE_WEEK_MILLIS
            TabAutoClose.ONE_MONTH -> System.currentTimeMillis() - ONE_MONTH_MILLIS
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
                    // Incognito tabs die with the process by definition.
                    if (tab.isIncognito || !url.startsWith("http", ignoreCase = true)) return@forEachIndexed
                    if (tab.id == state.activeTabId) activeIndex = size
                    add(
                        PersistedTab(
                            url = url,
                            originalUrl = tab.originalUrl,
                            routeId = tab.lastRedirect?.routeId,
                            frontendId = tab.lastRedirect?.frontendId,
                            instance = tab.lastRedirect?.instance,
                            createdAtEpochMillis = tab.createdAt,
                            group = tab.group,
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
        touchWebView(tabId)
        val existing = webViewPool[tabId]
        if (existing != null && webViewDarkModes[tabId] == darkPages) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        // A WebView's page theme is fixed at creation; carry its back/forward
        // history over to the replacement built with the new theme. A WebView
        // evicted by pool trimming left its history stashed in
        // [webViewSavedStates] for exactly this rebuild.
        val savedState =
            existing?.let { webView ->
                Bundle().also { webView.saveState(it) }
            } ?: webViewSavedStates.remove(tabId)
        if (existing != null) destroyWebView(tabId, keepHistoryForReuse = false)

        val tab = _uiState.value.tabs.find { it.id == tabId }
        val newWebView = buildWebView(tabId, context, darkPages, tab)

        webViewPool[tabId] = newWebView
        webViewDarkModes[tabId] = darkPages

        val restored = savedState?.let { newWebView.restoreState(it) } != null
        if (!restored) {
            loadInitialPage(newWebView, tabId, tab, isReplacement = existing != null)
        }
        trimWebViewPool()
        return newWebView
    }

    // Every branch configures one distinct WebView concern (profile, JS,
    // zoom, dark, desktop, bridge); splitting them would spread the setup
    // for one object across helpers.
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("CyclomaticComplexMethod")
    private fun buildWebView(
        tabId: String,
        context: Context,
        darkPages: Boolean,
        tab: BrowserTab?,
    ): WebView =
        WebView(WebViewTheming.themedContext(context, darkPages)).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            if (tab?.isIncognito == true && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                // Isolated cookie + site-storage profile; deleted again with the
                // last incognito tab (see [deleteIncognitoProfileIfUnused]).
                ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE_NAME)
                WebViewCompat.setProfile(this, INCOGNITO_PROFILE_NAME)
            }
            val site = siteSettingFor(tab)
            settings.apply {
                // 4.3: per-site JavaScript override; default stays on.
                javaScriptEnabled = site?.javaScriptEnabled ?: true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                textZoom = site?.textZoom ?: cachedSettings.browserTextZoom
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
            // 4.3: a site can force dark rendering regardless of the global switch.
            WebViewTheming.applyAutoDarken(this, darkPages, site?.forceDark ?: _uiState.value.autoDarken)
            applyDesktopMode(this, site?.desktopMode ?: (tab?.isDesktopMode == true))
            if (cachedSettings.backgroundAudioEnabled) {
                // 4.6: page media reports play/pause into the playback bus.
                // Bridge added only while the opt-in setting is on.
                addJavascriptInterface(MediaStateBridge(), MEDIA_BRIDGE_NAME)
            }
            if (site?.javaScriptEnabled ?: true) {
                addJavascriptInterface(BlobDownloadBridge(tabId), BLOB_BRIDGE_NAME)
            }
            configureDownloadAndContextListeners(tabId)
        }

    private fun WebView.configureDownloadAndContextListeners(tabId: String) {
        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            _uiState.update {
                it.copy(
                    findActiveMatchIndex = activeMatchOrdinal + 1,
                    findMatchCount = numberOfMatches,
                )
            }
        }
        setOnLongClickListener {
            val hit = hitTestResult
            val url = hit.extra
            val isImage =
                hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            val isLinkTarget =
                hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || isImage
            val isSupportedScheme =
                !url.isNullOrBlank() &&
                    (
                        url.startsWith("http://", ignoreCase = true) ||
                            url.startsWith("https://", ignoreCase = true) ||
                            (isImage && url.startsWith("data:", ignoreCase = true))
                    )
            if (isLinkTarget && isSupportedScheme) {
                showLinkContextMenu(url, isImage = isImage)
                true
            } else {
                false
            }
        }
        webViewClient = BrowserTabWebViewClient(tabId)
        webChromeClient = BrowserTabWebChromeClient(tabId)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            handleWebViewDownload(tabId, url, userAgent, contentDisposition, mimeType, contentLength)
        }
    }

    /** Effective per-site overrides for a tab's host (4.3); null = follow globals. */
    private fun siteSettingFor(tab: BrowserTab?): SiteSetting? =
        tab?.url?.let { url -> UrlParser.parse(url)?.host }?.let { host -> cachedSettings.siteSettingFor(host) }

    /** Current override for a host, for the site-settings sheet. */
    fun currentSiteSetting(host: String): SiteSetting? = cachedSettings.siteSettingFor(host)

    /**
     * Toolbar auto-hide with direction hysteresis (4.5). Scroll deltas are
     * tracked outside the state flow — writing state on every scroll frame
     * recomposes the whole browser screen and starves touch handling. The
     * state only updates when the hidden/showing decision actually flips.
     */
    fun onTabScroll(
        tabId: String,
        scrollY: Int,
    ) {
        val previous = lastScrollY[tabId]
        lastScrollY[tabId] = scrollY
        _uiState.update { current ->
            val tab = current.tabs.find { it.id == tabId } ?: return@update current
            if (!current.autoHideToolbar && !tab.toolbarHidden) return@update current
            val hidden =
                when {
                    !current.autoHideToolbar -> false
                    previous == null -> tab.toolbarHidden
                    scrollY - previous > SCROLL_DIRECTION_EPSILON && scrollY > TOOLBAR_HIDE_SCROLL_PX -> true
                    previous - scrollY > SCROLL_DIRECTION_EPSILON || scrollY <= TOOLBAR_SHOW_SCROLL_PX -> false
                    else -> tab.toolbarHidden
                }
            if (hidden == tab.toolbarHidden) {
                current
            } else {
                current.copy(tabs = current.tabs.map { if (it.id == tabId) it.copy(toolbarHidden = hidden) else it })
            }
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

    @Suppress("LongParameterList")
    private fun handleWebViewDownload(
        tabId: String,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val tab = _uiState.value.tabs.find { it.id == tabId } ?: _uiState.value.activeTab
        val isIncognito = tab?.isIncognito == true
        val referer = tab?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
        val resolvedUa = userAgent.ifBlank { WebSettings.getDefaultUserAgent(app) }
        val sourceHost = tab?.displayHost.orEmpty()

        when {
            url.startsWith("blob:", ignoreCase = true) ->
                prepareBlobDownload(
                    tabId = tabId,
                    blobUrl = url,
                    userAgent = resolvedUa,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    isIncognito = isIncognito,
                    sourceHost = sourceHost,
                )
            url.startsWith("data:", ignoreCase = true) ->
                prepareDataUriDownload(
                    dataUrl = url,
                    userAgent = resolvedUa,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    isIncognito = isIncognito,
                    sourceHost = sourceHost,
                )
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) ->
                prepareHttpDownload(
                    url = url,
                    userAgent = resolvedUa,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    referer = referer,
                    cookies = cookiesForDownload(url, isIncognito),
                    isIncognito = isIncognito,
                )
            else ->
                Toast.makeText(app, R.string.downloads_reason_invalid_url, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("LongParameterList")
    private fun prepareHttpDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        referer: String?,
        cookies: String?,
        isIncognito: Boolean,
    ) {
        val prepared = prepareDownloadUrl(url, cachedSettings)
        val platformGuess = URLUtil.guessFileName(prepared.cleanedUrl, contentDisposition, mimeType)
        val metadata =
            resolveInitialHttpDownloadMetadata(
                url = prepared.cleanedUrl,
                contentDisposition = contentDisposition,
                declaredMimeType = mimeType,
                platformGuess = platformGuess,
            )
        val suggestedName = metadata.fileName
        val resolvedMime = metadata.mimeType
        if (prepared.strippedCount > 0 || prepared.unwrappedLayers > 0) {
            addPrivacyStats(
                PrivacyStats(
                    strippedTrackingParams = prepared.strippedCount.toLong(),
                    unwrappedWrappers = prepared.unwrappedLayers.toLong(),
                ),
            )
        }
        val request =
            PendingDownloadRequest(
                id = UUID.randomUUID().toString(),
                originalUrl = url,
                cleanedUrl = prepared.cleanedUrl,
                strippedTrackingCount = prepared.strippedCount,
                sourceHost = prepared.sourceHost,
                sourceKind = DownloadSourceKind.HTTP,
                suggestedFileName = suggestedName,
                mimeType = resolvedMime,
                contentLength = contentLength,
                userAgent = userAgent,
                referer = referer,
                cookies = cookies,
                isIncognito = isIncognito,
                isPotentiallyDangerous = isPotentiallyDangerousFile(suggestedName, resolvedMime),
            )
        dispatchPendingOrStart(request)
    }

    @Suppress("LongParameterList")
    private fun prepareDataUriDownload(
        dataUrl: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        isIncognito: Boolean,
        sourceHost: String,
    ) {
        val decoded = parseDataUri(dataUrl)
        if (decoded == null) {
            downloadRepository.recordImmediateFailure(
                fileName = sanitizeFileName("download", mimeType),
                url = "data:...",
                sourceHost = sourceHost,
                sourceKind = DownloadSourceKind.DATA_URI,
                mimeType = mimeType ?: "application/octet-stream",
                isIncognito = isIncognito,
                reason = DownloadFailureReason.INVALID_URL,
            )
            Toast.makeText(app, R.string.downloads_reason_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        val effectiveMime = decoded.mimeType.ifBlank { mimeType.orEmpty() }.ifBlank { "application/octet-stream" }
        val rawGuess = URLUtil.guessFileName("https://data.local/download", contentDisposition, effectiveMime)
        val suggestedName = sanitizeFileName(rawGuess, effectiveMime)
        val request =
            PendingDownloadRequest(
                id = UUID.randomUUID().toString(),
                originalUrl = dataUrl,
                cleanedUrl = dataUrl,
                strippedTrackingCount = 0,
                sourceHost = sourceHost,
                sourceKind = DownloadSourceKind.DATA_URI,
                suggestedFileName = suggestedName,
                mimeType = guessMimeType(suggestedName, effectiveMime),
                contentLength = decoded.bytes.size.toLong(),
                userAgent = userAgent,
                referer = null,
                cookies = null,
                isIncognito = isIncognito,
                isPotentiallyDangerous = isPotentiallyDangerousFile(suggestedName, effectiveMime),
            )
        dispatchPendingOrStart(request)
    }

    @Suppress("LongParameterList")
    private fun prepareBlobDownload(
        tabId: String,
        blobUrl: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        isIncognito: Boolean,
        sourceHost: String,
    ) {
        val rawGuess = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        val suggestedName = sanitizeFileName(rawGuess, mimeType)
        val resolvedMime = guessMimeType(suggestedName, mimeType)
        val webView = webViewPool[tabId]
        val jsEnabled = webView?.settings?.javaScriptEnabled == true
        if (webView == null || !jsEnabled) {
            downloadRepository.recordImmediateFailure(
                fileName = suggestedName,
                url = blobUrl,
                sourceHost = sourceHost,
                sourceKind = DownloadSourceKind.BLOB_URI,
                mimeType = resolvedMime,
                isIncognito = isIncognito,
                reason = DownloadFailureReason.JS_DISABLED,
            )
            Toast.makeText(app, R.string.downloads_reason_js_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        val requestId = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        blobSessions[requestId] =
            BlobSession(
                requestId = requestId,
                tabId = tabId,
                nonce = nonce,
                originalUrl = blobUrl,
                sourceHost = sourceHost,
                suggestedName = suggestedName,
                mimeHint = mimeType,
                userAgent = userAgent,
                isIncognito = isIncognito,
            )
        webView.evaluateJavascript(blobPrepareJs(requestId, nonce, blobUrl), null)
    }

    private fun dispatchPendingOrStart(request: PendingDownloadRequest) {
        pendingMetadataProbeJob?.cancel()
        if (cachedSettings.askBeforeDownload || request.isPotentiallyDangerous) {
            _uiState.update { it.copy(pendingDownloadRequest = request) }
            if (shouldProbeDownloadMetadata(request)) {
                pendingMetadataProbeJob =
                    viewModelScope.launch {
                        val updated = downloadRepository.probeHttpMetadata(request) ?: return@launch
                        _uiState.update { state ->
                            if (state.pendingDownloadRequest?.id == request.id) {
                                state.copy(pendingDownloadRequest = updated)
                            } else {
                                state
                            }
                        }
                    }
            }
        } else {
            startDownloadInternal(
                request = request,
                fileName = request.suggestedFileName,
                wifiOnly = cachedSettings.downloadWifiOnly,
            )
        }
    }

    private fun shouldProbeDownloadMetadata(request: PendingDownloadRequest): Boolean =
        request.sourceKind == DownloadSourceKind.HTTP &&
            (
                request.contentLength <= 0L ||
                    isGenericMimeType(request.mimeType) ||
                    fileExtensionOf(request.suggestedFileName).let { it.isEmpty() || it == "bin" }
            )

    fun confirmPendingDownload(
        fileName: String,
        wifiOnly: Boolean,
    ) {
        pendingMetadataProbeJob?.cancel()
        val pending = _uiState.value.pendingDownloadRequest ?: return
        _uiState.update { it.copy(pendingDownloadRequest = null) }
        startDownloadInternal(pending, fileName, wifiOnly)
    }

    fun dismissPendingDownload() {
        pendingMetadataProbeJob?.cancel()
        val pending = _uiState.value.pendingDownloadRequest ?: return
        _uiState.update { it.copy(pendingDownloadRequest = null) }
        val reqId = pending.blobRequestId
        val webView = pending.tabId?.let { webViewPool[it] }
        if (reqId != null) {
            blobSessions.remove(reqId)
            val js =
                "if(window.__lrPendingBlobs){delete window.__lrPendingBlobs[${JSONObject.quote(reqId)}];}"
            webView?.evaluateJavascript(js, null)
        }
    }

    private fun startDownloadInternal(
        request: PendingDownloadRequest,
        fileName: String,
        wifiOnly: Boolean,
    ) {
        when (request.sourceKind) {
            DownloadSourceKind.HTTP ->
                downloadRepository.enqueueHttp(request, fileName, wifiOnly)
            DownloadSourceKind.DATA_URI ->
                downloadRepository.enqueueDataUri(request, fileName)
            DownloadSourceKind.BLOB_URI -> {
                val reqId = request.blobRequestId ?: return
                val session = blobSessions[reqId]
                val webView = request.tabId?.let { webViewPool[it] }
                val downloadId = downloadRepository.beginBlobDownload(request, fileName)
                if (session == null || webView == null) {
                    blobSessions.remove(reqId)
                    downloadRepository.failBlobDownload(downloadId, "Tab closed")
                    return
                }
                session.downloadId = downloadId
                webView.evaluateJavascript(
                    blobStreamJs(reqId, session.nonce, request.originalUrl),
                    null,
                )
            }
        }
    }

    private fun cookiesForDownload(
        url: String,
        isIncognito: Boolean,
    ): String? {
        val manager =
            when {
                !isIncognito -> CookieManager.getInstance()
                !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) -> null
                else ->
                    runCatching {
                        ProfileStore.getInstance().getProfile(INCOGNITO_PROFILE_NAME)?.cookieManager
                    }.getOrNull()
            }
        return manager?.let { runCatching { it.getCookie(url) }.getOrNull() }
    }

    private fun blobPrepareJs(
        requestId: String,
        nonce: String,
        blobUrl: String,
    ): String {
        val qUrl = JSONObject.quote(blobUrl)
        val qId = JSONObject.quote(requestId)
        val qNonce = JSONObject.quote(nonce)
        return """
            (function(){
              var b=window.__lrPendingBlobs=window.__lrPendingBlobs||{};
              fetch($qUrl).then(function(r){return r.blob();}).then(function(blob){
                b[$qId]=blob;
                window.$BLOB_BRIDGE_NAME.onBlobPrepared($qId,$qNonce,blob.size||-1,blob.type||'');
              }).catch(function(e){
                window.$BLOB_BRIDGE_NAME.onBlobPrepareError($qId,$qNonce,String(e||'error'));
              });
            })();
        """.trimIndent()
    }

    private fun blobStreamJs(
        requestId: String,
        nonce: String,
        fallbackBlobUrl: String,
    ): String {
        val qId = JSONObject.quote(requestId)
        val qNonce = JSONObject.quote(nonce)
        val qUrl = JSONObject.quote(fallbackBlobUrl)
        val bridge = BLOB_BRIDGE_NAME
        return """
            (function(){
              var s=window.__lrPendingBlobs||{};
              var cached=s[$qId];
              if(s[$qId]){delete s[$qId];}
              var getB=cached?Promise.resolve(cached):fetch($qUrl).then(function(r){return r.blob();});
              getB.then(function(blob){
                var total=blob.size||0;
                var chunk=$BLOB_CHUNK_BYTES;
                if(total===0){window.$bridge.onBlobChunk($qId,$qNonce,'',true,0);return;}
                var off=0;
                function next(){
                  var end=Math.min(off+chunk,total);
                  var rd=new FileReader();
                  rd.onloadend=function(){
                    try{
                      var v=String(rd.result||'');
                      var idx=v.indexOf(',');
                      var b64=idx>=0?v.substring(idx+1):'';
                      var last=end>=total;
                      window.$bridge.onBlobChunk($qId,$qNonce,b64,last,total);
                      if(!last){off=end;next();}
                    }catch(err){window.$bridge.onBlobError($qId,$qNonce,String(err||'read'));}
                  };
                  rd.onerror=function(){window.$bridge.onBlobError($qId,$qNonce,'FileReader error');};
                  rd.readAsDataURL(blob.slice(off,end));
                }
                next();
              }).catch(function(e){window.$bridge.onBlobError($qId,$qNonce,String(e||'blob'));});
            })();
        """.trimIndent()
    }

    inner class BlobDownloadBridge(
        private val tabId: String,
    ) {
        @JavascriptInterface
        fun onBlobPrepared(
            requestId: String,
            nonce: String,
            totalBytes: Long,
            mimeType: String?,
        ) {
            val session = blobSessions[requestId] ?: return
            if (session.nonce != nonce || session.tabId != tabId) return
            viewModelScope.launch {
                val activeSession = blobSessions[requestId] ?: return@launch
                val resolvedMime =
                    guessMimeType(
                        activeSession.suggestedName,
                        mimeType?.ifBlank { null } ?: activeSession.mimeHint,
                    )
                val finalName =
                    sanitizeFileName(
                        ensureFileExtension(
                            activeSession.suggestedName,
                            activeSession.suggestedName,
                            resolvedMime,
                        ),
                        resolvedMime,
                    )
                val request =
                    PendingDownloadRequest(
                        id = UUID.randomUUID().toString(),
                        originalUrl = activeSession.originalUrl,
                        cleanedUrl = activeSession.originalUrl,
                        strippedTrackingCount = 0,
                        sourceHost = activeSession.sourceHost,
                        sourceKind = DownloadSourceKind.BLOB_URI,
                        suggestedFileName = finalName,
                        mimeType = resolvedMime,
                        contentLength = totalBytes,
                        userAgent = activeSession.userAgent,
                        referer = null,
                        cookies = null,
                        isIncognito = activeSession.isIncognito,
                        isPotentiallyDangerous = isPotentiallyDangerousFile(finalName, resolvedMime),
                        blobRequestId = requestId,
                        tabId = tabId,
                    )
                dispatchPendingOrStart(request)
            }
        }

        @JavascriptInterface
        fun onBlobPrepareError(
            requestId: String,
            nonce: String,
            detail: String?,
        ) {
            val session = blobSessions[requestId] ?: return
            if (session.nonce != nonce || session.tabId != tabId) return
            blobSessions.remove(requestId)
            viewModelScope.launch {
                val resolvedMime = guessMimeType(session.suggestedName, session.mimeHint)
                downloadRepository.recordImmediateFailure(
                    fileName = session.suggestedName,
                    url = session.originalUrl,
                    sourceHost = session.sourceHost,
                    sourceKind = DownloadSourceKind.BLOB_URI,
                    mimeType = resolvedMime,
                    isIncognito = session.isIncognito,
                    reason = DownloadFailureReason.BLOB_ERROR,
                    detail = detail,
                )
            }
        }

        @JavascriptInterface
        fun onBlobChunk(
            requestId: String,
            nonce: String,
            base64Chunk: String,
            isLast: Boolean,
            totalHintBytes: Long,
        ) {
            val session = blobSessions[requestId]?.takeIf { it.nonce == nonce && it.tabId == tabId }
            val downloadId = session?.downloadId ?: return
            downloadRepository.appendBlobChunk(
                id = downloadId,
                base64Chunk = base64Chunk,
                isLast = isLast,
                totalHintBytes = totalHintBytes,
            )
            if (isLast) {
                blobSessions.remove(requestId)
            }
        }

        @JavascriptInterface
        fun onBlobError(
            requestId: String,
            nonce: String,
            detail: String?,
        ) {
            val session = blobSessions[requestId]?.takeIf { it.nonce == nonce && it.tabId == tabId } ?: return
            blobSessions.remove(requestId)
            session.downloadId?.let { downloadRepository.failBlobDownload(it, detail) }
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

    // --- content blocking (4.2) ---

    @Suppress("ReturnCount")
    private fun blockingEnabledForTab(tabId: String): Boolean {
        if (!cachedSettings.contentBlockerEnabled) return false
        val tab = _uiState.value.tabs.find { it.id == tabId } ?: return true
        val site = siteSettingFor(tab)
        return site?.contentBlocking ?: true
    }

    private fun incrementBlockedCount(tabId: String) {
        _uiState.update { current ->
            current.copy(
                tabs = current.tabs.map { if (it.id == tabId) it.copy(blockedCount = it.blockedCount + 1) else it },
            )
        }
        addPrivacyStats(PrivacyStats(blockedRequests = 1))
    }

    private fun resetBlockedCount(tabId: String) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map {
                        if (it.id == tabId && it.blockedCount != 0) {
                            it.copy(
                                blockedCount = 0,
                            )
                        } else {
                            it
                        }
                    },
            )
        }
    }

    // --- automatic failover (2.2) ---

    private fun isPendingRedirect(tabId: String): Boolean =
        _uiState.value.tabs
            .find { it.id == tabId }
            ?.let { tab -> pendingRedirectTargets[tabId]?.contains(tab.url) == true }
            ?: false

    /**
     * A routed main-frame load just failed (network error, 5xx/429, TLS,
     * bot interstitial): mark the serving instance unhealthy and retry on
     * the next healthy instance, at most [InstanceFailover.MAX_ATTEMPTS]
     * times before the in-app error page takes over.
     */
    @Suppress("ReturnCount")
    private fun handleRoutedLoadFailure(tabId: String) {
        val tab = _uiState.value.tabs.find { it.id == tabId } ?: return
        val alternatives = computeInstanceAlternatives(tab)
        if (alternatives == null) {
            // Not a routed tab (or no alternatives): plain error page.
            setPageError(tabId, true)
            return
        }
        val retry = resolveFailoverTarget(tab, alternatives)
        if (retry == null) {
            setPageError(tabId, true)
            return
        }
        applyFailover(tabId, tab, alternatives, retry)
    }

    /** Decides the next instance (or null = give up) after a routed failure. */
    private fun resolveFailoverTarget(
        tab: BrowserTab,
        alternatives: InstanceAlternatives,
    ): String? {
        val failedInstance =
            tab.lastRedirect?.instance
                ?: alternatives.instances.firstOrNull { instance ->
                    BrowserTab.hostOf(tab.url) == BrowserTab.hostOf(instance)
                }
        failedInstance?.let { markInstanceFailed(it) }
        if (!cachedSettings.autoFailoverEnabled) return null
        return InstanceFailover.nextInstance(
            instances = alternatives.instances,
            failedInstance = failedInstance,
            attemptsDone = tab.failoverAttempts,
            health = cachedHealth,
            nowMillis = System.currentTimeMillis(),
        )
    }

    /** Re-routes the tab's original link onto [nextInstance] and reloads it. */
    @Suppress("ReturnCount")
    private fun applyFailover(
        tabId: String,
        tab: BrowserTab,
        alternatives: InstanceAlternatives,
        nextInstance: String,
    ) {
        val manifest = cachedManifest ?: return
        val context =
            cachedSettings
                .routingContext()
                .copy(
                    instanceOverrides = mapOf("${alternatives.routeId}/${alternatives.frontendId}" to nextInstance),
                    instanceHealth = cachedHealth,
                    nowMillis = System.currentTimeMillis(),
                )
        val original = tab.originalUrl ?: return
        val result = UrlRouter(manifest).resolve(original, context)
        if (result !is RoutingResult.Redirect || result.redirectedUrl == tab.url) return
        pendingRedirectTargets.getOrPut(tabId) { mutableSetOf() }.add(result.redirectedUrl)
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { t ->
                        if (t.id == tabId) {
                            t.copy(
                                url = result.redirectedUrl,
                                failoverAttempts = t.failoverAttempts + 1,
                                lastRedirect =
                                    t.lastRedirect?.copy(
                                        redirectedUrl = result.redirectedUrl,
                                        instance = result.instance,
                                    ),
                            )
                        } else {
                            t
                        }
                    },
            )
        }
        webViewPool[tabId]?.loadUrl(result.redirectedUrl)
    }

    private fun markInstanceFailed(instance: String) {
        val now = System.currentTimeMillis()
        cachedHealth =
            cachedHealth + (
                instance to
                    InstanceHealth(
                        latencyMillis = cachedHealth[instance]?.latencyMillis,
                        lastFailedAtMillis = now,
                    )
            )
        viewModelScope.launch {
            browserDataRepository.recordInstanceFailure(instance, now)
        }
    }

    // --- per-site settings (4.3) ---

    fun updateSiteSetting(
        host: String,
        transform: (SiteSetting) -> SiteSetting,
    ) {
        val current = cachedSettings.siteSettingFor(host) ?: SiteSetting()
        val updated = transform(current)
        viewModelScope.launch {
            settingsRepository.setSiteSetting(host, updated)
            // A JS toggle only bites on the next load; the others apply
            // live where possible, so reload the matching tabs either way.
            _uiState.value.tabs
                .filter { tab -> BrowserTab.hostOf(tab.url).equals(host, ignoreCase = true) }
                .forEach { tab ->
                    val webView = webViewPool[tab.id] ?: return@forEach
                    val site = updated
                    webView.settings.javaScriptEnabled = site.javaScriptEnabled ?: true
                    (site.textZoom ?: cachedSettings.browserTextZoom).let { webView.settings.textZoom = it }
                    webView.reload()
                }
        }
    }

    fun resetSiteSetting(host: String) {
        viewModelScope.launch {
            settingsRepository.setSiteSetting(host, null)
            _uiState.value.tabs
                .filter { BrowserTab.hostOf(it.url).equals(host, ignoreCase = true) }
                .forEach { tab -> webViewPool[tab.id]?.reload() }
        }
    }

    // --- print / PDF (4.5) ---

    fun printActiveTab(context: Context) {
        val webView = webViewPool[_uiState.value.activeTabId] ?: return
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager ?: return
        val title = _uiState.value.activeTab?.title?.ifBlank { "page" } ?: "page"
        printManager.print(title, webView.createPrintDocumentAdapter(title), null)
    }

    // --- downloads manager ---

    fun setDownloadsSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(isDownloadsSheetVisible = visible) }
        if (visible) {
            downloadRepository.refreshFileAvailability()
        }
    }

    fun openDownloadsFromNotification() {
        _uiState.update { it.withOverlaysClosed().copy(isDownloadsSheetVisible = true) }
        downloadRepository.refreshFileAvailability()
        requestBrowserFocus()
    }

    fun setAskBeforeDownloadPreference(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAskBeforeDownload(enabled) }
    }

    fun setDownloadWifiOnlyPreference(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDownloadWifiOnly(enabled) }
    }

    fun pauseDownload(id: String) {
        downloadRepository.pause(id)
    }

    fun pauseAllDownloads() {
        downloadRepository.pauseAll()
    }

    fun resumeDownload(
        id: String,
        allowMeteredOverride: Boolean = false,
    ) {
        downloadRepository.resume(id, allowMeteredOverride)
    }

    fun cancelDownload(id: String) {
        downloadRepository.cancel(id)
    }

    fun retryDownload(id: String) {
        downloadRepository.retry(id)
    }

    fun removeDownload(
        id: String,
        deleteFile: Boolean,
    ) {
        downloadRepository.removeEntry(id, deleteFile)
    }

    fun clearFinishedDownloads(deleteFiles: Boolean) {
        downloadRepository.clearFinished(deleteFiles)
    }

    fun openDownloadedFile(
        context: Context,
        item: DownloadItem,
    ): OpenFileResult {
        val result = downloadStorage.openFile(context, item)
        if (result == OpenFileResult.FILE_MISSING) {
            downloadRepository.refreshFileAvailability()
        }
        return result
    }

    fun shareDownloadedFile(
        context: Context,
        item: DownloadItem,
    ): Boolean = downloadStorage.shareFile(context, item, context.getString(R.string.downloads_share_title))

    fun copyDownloadUrl(url: String) {
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("download_url", url))
        Toast.makeText(app, R.string.downloads_url_copied, Toast.LENGTH_SHORT).show()
    }

    fun openSystemDownloads(context: Context): Boolean = downloadStorage.openSystemDownloads(context)

    // --- tab switcher extras (4.5) ---

    fun closeOtherTabs(keepTabId: String) {
        _uiState.value.tabs.filter { it.id != keepTabId }.forEach { tab ->
            destroyWebView(tab.id, keepHistoryForReuse = false)
            pendingRedirectTargets.remove(tab.id)
        }
        _uiState.update { current ->
            val kept = current.tabs.filter { it.id == keepTabId }
            if (kept.isEmpty()) return@update current
            current.copy(tabs = kept, activeTabId = keepTabId, isTabsSheetVisible = false)
        }
        deleteIncognitoProfileIfUnused()
        scheduleSessionPersist(immediate = true)
    }

    fun assignTabGroup(
        tabId: String,
        group: String?,
    ) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map {
                        if (it.id == tabId) {
                            it.copy(
                                group = group?.trim()?.ifBlank { null },
                            )
                        } else {
                            it
                        }
                    },
            )
        }
        scheduleSessionPersist()
    }

    fun closeIncognitoTabs() {
        _uiState.value.tabs.filter { it.isIncognito }.forEach { tab -> closeTab(tab.id) }
    }

    // --- reader mode (4.4) ---

    /** Toggles the local reader view; exit reloads the original page. */
    fun toggleReaderMode() {
        val tab = _uiState.value.activeTab ?: return
        val webView = webViewPool[tab.id] ?: return
        if (tab.isReaderMode) {
            _uiState.update {
                    current ->
                current.copy(tabs = current.tabs.map { if (it.id == tab.id) it.copy(isReaderMode = false) else it })
            }
            webView.reload()
        } else {
            _uiState.update {
                    current ->
                current.copy(tabs = current.tabs.map { if (it.id == tab.id) it.copy(isReaderMode = true) else it })
            }
            readerJs()?.let { js -> webView.evaluateJavascript(js, null) }
        }
    }

    // --- original link actions (2.7) ---

    /**
     * The tab's original link: the pre-redirect URL when known, otherwise
     * the deterministic reverse of the frontend URL ("never guess" — null
     * when it cannot be computed).
     */
    @Suppress("ReturnCount") // guard chain: known original → reverse → give up
    fun originalLinkForActiveTab(): String? {
        val tab = _uiState.value.activeTab ?: return null
        tab.originalUrl?.let { return it }
        val manifest = cachedManifest ?: return null
        return ReverseRouter.reverse(tab.url, manifest)
    }

    fun shareOriginalLink(context: Context) {
        val original = originalLinkForActiveTab()
        if (original == null) {
            Toast.makeText(app, R.string.browser_original_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val sendIntent =
            Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, original)
                type = "text/plain"
            }
        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.browser_share_link_title)))
    }

    fun copyOriginalLink() {
        val original = originalLinkForActiveTab()
        if (original == null) {
            Toast.makeText(app, R.string.browser_original_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("link", original))
        Toast.makeText(app, R.string.browser_link_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * JS bridge the page uses (when the opt-in setting is on) to report
     * media playback state; only booleans cross the boundary.
     */
    inner class MediaStateBridge {
        @android.webkit.JavascriptInterface
        fun onMediaState(playing: Boolean) {
            MediaPlaybackBus.reportPlaying(playing)
        }
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

    private fun setPageError(
        tabId: String,
        hasError: Boolean,
    ) {
        _uiState.update { current ->
            current.copy(
                tabs =
                    current.tabs.map { tab ->
                        if (tab.id == tabId && tab.pageError != hasError) {
                            tab.copy(pageError = hasError)
                        } else {
                            tab
                        }
                    },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        pendingFileChooserCallback?.onReceiveValue(null)
        webViewPool.values.forEach { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        webViewPool.clear()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            _uiState.value.tabs.none { it.isIncognito }
        ) {
            ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE_NAME)
        }
    }

    private inner class BrowserTabWebViewClient(private val tabId: String) : WebViewClient() {
        /**
         * Content blocking (4.2): fast host/suffix denylist check before any
         * request leaves the app. The blocklist is cached in memory by the
         * store; per-site overrides can switch blocking off for a host.
         */
        @Suppress("ReturnCount") // guard chain over the blocking path
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            val host = url.host?.lowercase() ?: return null
            if (!blockingEnabledForTab(tabId)) return null
            if (!ContentBlocker.shouldBlock(host, blocklistStore.activeBlocklist())) return null
            incrementBlockedCount(tabId)
            return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        }

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
            // A new navigation attempt starts with a clean slate: the error
            // page of the previous load must not linger over fresh content,
            // and the blocker counter belongs to the page being loaded now.
            setPageError(tabId, false)
            resetBlockedCount(tabId)
            if (_uiState.value.isFullscreenVideo) clearFullscreenVideoState()
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

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            // Subresource failures (favicon, analytics…) keep the page usable;
            // a main-frame failure is a failover candidate (2.2) before it is
            // an error page.
            if (request?.isForMainFrame == true) handleRoutedLoadFailure(tabId)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request.isForMainFrame && InstanceFailover.isRetryableHttpError(errorResponse.statusCode)) {
                handleRoutedLoadFailure(tabId)
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            super.onReceivedSslError(view, handler, error)
            // Pinned privacy posture: never proceed past an invalid
            // certificate. If the main frame is the one failing (nothing
            // loaded yet), failover — or surface the error page.
            handler?.cancel()
            if (view?.url == null || view.url.equals("about:blank", ignoreCase = true)) {
                handleRoutedLoadFailure(tabId)
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
                if (InstanceFailover.looksLikeBotInterstitial(pageTitle) && !isPendingRedirect(tabId)) {
                    handleRoutedLoadFailure(tabId)
                }
                MediaPlaybackBus.setMeta(pageTitle, BrowserTab.hostOf(url))
                injectPageScripts(view)
                val isIncognito = _uiState.value.tabs.find { it.id == tabId }?.isIncognito == true
                if (!isIncognito &&
                    url != BrowserTab.DEFAULT_HOME_URL &&
                    !url.equals("about:blank", ignoreCase = true)
                ) {
                    val limit = _uiState.value.let { cachedSettings.historyLimit }.coerceAtLeast(0)
                    viewModelScope.launch {
                        browserDataRepository.addHistory(pageTitle, url, limit)
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

        /**
         * Page-level JS hooks (4.4/4.6): the media-state bridge reports
         * play/pause through [MediaPlaybackBus]; the reader-mode script is
         * idempotent and only acts when invoked.
         */
        private fun injectPageScripts(view: WebView?) {
            view ?: return
            val tab = _uiState.value.tabs.find { it.id == tabId } ?: return
            if (cachedSettings.backgroundAudioEnabled) {
                mediaHookJs()?.let { js -> view.evaluateJavascript(js, null) }
            }
            if (tab.isReaderMode) {
                readerJs()?.let { js -> view.evaluateJavascript(js, null) }
            }
        }
    }

    private inner class BrowserTabWebChromeClient(private val tabId: String) : WebChromeClient() {
        override fun onShowCustomView(
            view: View?,
            callback: CustomViewCallback?,
        ) {
            super.onShowCustomView(view, callback)
            if (view != null && callback != null) {
                enterFullscreenVideo(view, callback)
            }
        }

        override fun onHideCustomView() {
            super.onHideCustomView()
            if (!_uiState.value.isFullscreenVideo) return
            customViewCallback = null
            clearFullscreenVideoState()
        }

        @Suppress("ReturnCount")
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMessage: Message?,
        ): Boolean {
            val transport = resultMessage?.obj as? WebView.WebViewTransport ?: return false
            val source = view ?: return false
            // target=_blank / window.open: a throwaway WebView receives the new
            // window's first navigation, which we capture and turn into a
            // regular tab. The throwaway is never attached, so it renders
            // nothing and is destroyed right after the URL arrives (plus a
            // safety cleanup for pages that never navigate it).
            val popup = WebView(source.context)
            popup.settings.javaScriptEnabled = true
            val disposed = booleanArrayOf(false)

            fun disposePopup() {
                if (disposed[0]) return
                disposed[0] = true
                popup.webViewClient = WebViewClient()
                popup.destroy()
            }
            popup.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        target: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (request.isForMainFrame && url.startsWith("http", ignoreCase = true)) {
                            openNewTab(url)
                            disposePopup()
                        }
                        return true
                    }
                }
            transport.webView = popup
            resultMessage.sendToTarget()
            viewModelScope.launch {
                delay(POPUP_CAPTURE_TIMEOUT_MILLIS)
                disposePopup()
            }
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            return requestFileChooser(filePathCallback, fileChooserParams)
        }

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

    // --- page scripts (4.4/4.6), loaded once from assets ---

    @Volatile
    private var cachedMediaHookJs: String? = null

    @Volatile
    private var cachedMediaToggleJs: String? = null

    @Volatile
    private var cachedReaderJs: String? = null

    private fun assetScript(
        name: String,
        cached: String?,
    ): String? {
        cached?.let { return it }
        return try {
            app.assets.open(name).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun mediaHookJs(): String? {
        val value = assetScript("media_hook.js", cachedMediaHookJs) ?: return null
        cachedMediaHookJs = value
        return value
    }

    private fun mediaToggleJs(): String? {
        val value = assetScript("media_toggle.js", cachedMediaToggleJs) ?: return null
        cachedMediaToggleJs = value
        return value
    }

    private fun readerJs(): String? {
        val value = assetScript("reader.js", cachedReaderJs) ?: return null
        cachedReaderJs = value
        return value
    }

    private companion object {
        const val SESSION_SAVE_DEBOUNCE_MILLIS = 500L
        const val MAX_RESTORED_TABS = 50
        const val THUMBNAIL_WIDTH_PX = 240

        /** WebViews kept alive across background tabs; beyond this, LRU eviction kicks in. */
        const val MAX_ALIVE_WEB_VIEWS = 8

        /** How long a target=_blank capture WebView may linger before being force-destroyed. */
        const val POPUP_CAPTURE_TIMEOUT_MILLIS = 15_000L

        /** Name of the ephemeral WebView profile backing incognito tabs. */
        const val INCOGNITO_PROFILE_NAME = "incognito"

        /** Delay before retrying an in-use incognito profile deletion. */
        const val PROFILE_DELETE_RETRY_MILLIS = 1_000L

        const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
        const val ONE_WEEK_MILLIS = 7L * ONE_DAY_MILLIS
        const val ONE_MONTH_MILLIS = 30L * ONE_DAY_MILLIS

        const val SCROLL_DIRECTION_EPSILON = 8
        const val TOOLBAR_SHOW_SCROLL_PX = 64
        const val TOOLBAR_HIDE_SCROLL_PX = 300

        /** Name of the JS bridge pages use to report media state (4.6). */
        const val MEDIA_BRIDGE_NAME = "AndroidMedia"

        /** Name of the JS bridge used for nonce-gated blob download streaming. */
        const val BLOB_BRIDGE_NAME = "AndroidBlobDownload"

        /** Chunk size (bytes) per base64 bridge hop when streaming blobs. */
        const val BLOB_CHUNK_BYTES = 262_144
    }
}
