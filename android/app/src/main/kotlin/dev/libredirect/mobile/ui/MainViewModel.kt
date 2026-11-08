package dev.libredirect.mobile.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.libredirect.mobile.BuildConfig
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.MainActivity
import dev.libredirect.mobile.R
import dev.libredirect.mobile.blocker.BlocklistFetcher
import dev.libredirect.mobile.blocker.BlocklistStore
import dev.libredirect.mobile.browser.BrowserInfo
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.browser.ShortcutActions
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.routing.RoutingOutcome
import dev.libredirect.mobile.core.routing.RoutingTrace
import dev.libredirect.mobile.core.routing.TraceReason
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.diagnostics.DiagnosticsLog
import dev.libredirect.mobile.manifest.InstanceHealthWorker
import dev.libredirect.mobile.manifest.InstanceRefreshWorker
import dev.libredirect.mobile.manifest.RefreshResult
import dev.libredirect.mobile.media.MediaPlaybackBus
import dev.libredirect.mobile.media.MediaPlaybackService
import dev.libredirect.mobile.redirect.LinkPipeline
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.RouteMode
import dev.libredirect.mobile.settings.SettingsBackupCodec
import dev.libredirect.mobile.settings.SettingsRepository
import dev.libredirect.mobile.settings.TabAutoClose
import dev.libredirect.mobile.ui.browser.BrowserDataRepository
import dev.libredirect.mobile.ui.browser.BrowserViewModel
import dev.libredirect.mobile.ui.browser.SearchEngine
import dev.libredirect.mobile.update.UpdateCheckResult
import dev.libredirect.mobile.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// The settings plumbing is intentionally consolidated here: every Settings
// card maps 1:1 to a small setter, and splitting those across helpers would
// add indirection without removing code.
@Suppress("TooManyFunctions", "LargeClass")
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<LibRedirectApplication>()
    private val settingsRepository = SettingsRepository(app)
    private val manifestRepository = app.manifestRepository
    private val browserLauncher = BrowserLauncher(app)
    private val browserDataRepository = BrowserDataRepository(app)
    private val updateRepository = UpdateRepository(settingsRepository)
    private val blocklistStore = BlocklistStore(app)
    private val blocklistFetcher = BlocklistFetcher()

    @Volatile
    private var cachedBlocklistHostCount: Int = 0

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /**
     * True while the app lock requires authentication before any content is
     * shown. Set from MainActivity when the app lock setting is on and the
     * activity leaves the foreground; cleared by a successful biometric or
     * device-credential authentication.
     */
    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    init {
        // A cold start (fresh process: icon tap after task removal, process
        // death in the background) never runs onStop, so the backgrounding
        // hook in MainActivity can't arm the lock — arm it here, before any
        // content is shown, whenever the feature is on.
        launchSafely {
            if (settingsRepository.settings.first().appLockEnabled) {
                _isAppLocked.value = true
            }
        }
    }

    fun lockApp() {
        if (latestSettings.appLockEnabled) _isAppLocked.value = true
    }

    fun unlockApp() {
        _isAppLocked.value = false
    }

    /**
     * Navigation route requested by a launcher shortcut (set from
     * [dev.libredirect.mobile.MainActivity]; consumed by AppNavHost once it
     * has navigated).
     */
    private val _shortcutRoute = MutableStateFlow<String?>(null)
    val shortcutRoute: StateFlow<String?> = _shortcutRoute.asStateFlow()

    fun onShortcutRoute(route: String) {
        _shortcutRoute.value = route
    }

    fun consumeShortcutRoute() {
        _shortcutRoute.value = null
    }

    private var manifest: Manifest? = null
    private var manifestErrorMessage: String? = null
    private var browsers: List<BrowserInfo> = emptyList()
    private var customRoutes: List<Route> = emptyList()
    private var latestSettings: AppSettings = AppSettings()
    private var lastClipboardOfferUrl: String? = null

    init {
        launchSafely {
            manifest = loadManifest()
            customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
            browsers = withContext(Dispatchers.IO) { browserLauncher.installedBrowsers() }
            settingsRepository.settings.collect { settings ->
                latestSettings = settings
                _uiState.value = buildUiState(settings)
            }
        }
        launchSafely {
            // Surface a previously found release immediately, then re-check
            // against GitHub Releases at most once per auto-check interval.
            val cached = updateRepository.cachedUpdate(currentVersionName())
            if (cached != null) {
                _uiState.value = _uiState.value.copy(updateAvailable = cached, updateChecked = true)
            }
            if (updateRepository.shouldAutoCheck()) {
                checkForUpdates()
            }
        }
        launchSafely {
            regenerateManifestSilently()
        }
        launchSafely {
            manifestRepository.generationNotes.collect { notes ->
                _uiState.value = _uiState.value.copy(generationNotes = notes)
            }
        }
        launchSafely {
            browserDataRepository.redirectStats.collect { stats ->
                _uiState.value = _uiState.value.copy(redirectStats = stats)
            }
        }
        launchSafely {
            browserDataRepository.privacyStats.collect { stats ->
                _uiState.value = _uiState.value.copy(privacyStats = stats)
            }
        }
        launchSafely {
            cachedBlocklistHostCount = withContext(Dispatchers.IO) { blocklistStore.activeBlocklist().size }
            _uiState.value = _uiState.value.copy(blocklistHostCount = cachedBlocklistHostCount)
        }
        launchSafely {
            val lastAt = browserDataRepository.lastLatencyMeasureAt()
            val count = browserDataRepository.lastLatencyMeasureCount()
            _uiState.value =
                _uiState.value.copy(
                    lastLatencyMeasureAt = lastAt,
                    lastLatencyMeasureCount = count,
                )
        }
    }

    /**
     * The upstream sources are the manifest's primary source, so a stale
     * routing structure is refreshed in the background on startup — silently:
     * the bundled/active manifest keeps routing meanwhile, and a failure
     * only costs a log line (the daily worker and the manual refresh are
     * independent paths). Throttled by the last *attempt*, so a dead network
     * is not hammered on every launch.
     */
    private suspend fun regenerateManifestSilently() {
        val now = System.currentTimeMillis()
        if (now - settingsRepository.manifestLastGenerationAt() < SILENT_GENERATION_INTERVAL_MS) return
        settingsRepository.setManifestLastGenerationAt(now)
        when (val result = manifestRepository.refresh()) {
            is RefreshResult.Activated -> {
                manifest = loadManifest()
                val settings = settingsRepository.settings.first()
                _uiState.value = buildUiState(settings)
                Log.i(TAG, "Manifest regenerated silently to revision ${result.revision}")
            }
            else -> Log.i(TAG, "Silent manifest generation skipped: $result")
        }
    }

    private fun versionInfo(): Triple<String, Long, String> =
        try {
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            val name = packageInfo.versionName ?: BuildConfig.VERSION_NAME
            val code =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            Triple(name, code, "v$name (Build $code)")
        } catch (_: Exception) {
            val code = BuildConfig.VERSION_CODE.toLong()
            Triple(BuildConfig.VERSION_NAME, code, "v${BuildConfig.VERSION_NAME} (Build $code)")
        }

    private fun currentVersionName(): String = versionInfo().first

    fun installedBrowsers(): List<BrowserInfo> = browsers

    fun errorShown() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Checks GitHub Releases for a newer version. Triggered manually from the
     * settings app info card and automatically (throttled) at startup.
     */
    fun checkForUpdates() {
        if (_uiState.value.updateCheckInProgress) return
        _uiState.value = _uiState.value.copy(updateCheckInProgress = true, updateCheckFailed = false)
        launchSafely {
            when (val result = updateRepository.checkNow(currentVersionName())) {
                is UpdateCheckResult.Available ->
                    _uiState.value =
                        _uiState.value.copy(
                            updateAvailable = result.info,
                            updateChecked = true,
                            updateCheckInProgress = false,
                        )

                UpdateCheckResult.UpToDate ->
                    _uiState.value =
                        _uiState.value.copy(
                            updateAvailable = null,
                            updateChecked = true,
                            updateCheckInProgress = false,
                        )

                UpdateCheckResult.Failed ->
                    _uiState.value =
                        _uiState.value.copy(updateCheckFailed = true, updateCheckInProgress = false)
            }
        }
    }

    fun setRoutingEnabled(enabled: Boolean) = launchSafely { settingsRepository.setRoutingEnabled(enabled) }

    /** Three-state service switch (2.4): ALWAYS / ASK / OFF. */
    fun setRouteMode(
        routeId: String,
        mode: RouteMode,
    ) = launchSafely { settingsRepository.setRouteMode(routeId, mode) }

    fun setRouteEnabled(
        routeId: String,
        enabled: Boolean,
    ) = launchSafely { settingsRepository.setRouteEnabled(routeId, enabled) }

    // --- 2.6 pause ---

    /** Pauses routing for [minutes] (null = until the user resumes). */
    fun pauseRouting(minutes: Int?) {
        val until =
            if (minutes == null) {
                PAUSE_INDEFINITE
            } else {
                System.currentTimeMillis() + minutes * MILLIS_PER_MINUTE
            }
        launchSafely { settingsRepository.setPausedUntil(until) }
    }

    fun resumeRouting() = launchSafely { settingsRepository.setPausedUntil(0L) }

    // --- 3.x privacy toggles ---

    fun setUnwrapWrappersEnabled(enabled: Boolean) =
        launchSafely { settingsRepository.setUnwrapRedirectWrappersEnabled(enabled) }

    fun setShortLinkResolveEnabled(enabled: Boolean) =
        launchSafely { settingsRepository.setShortLinkResolveEnabled(enabled) }

    fun resetPrivacyStats() = launchSafely { browserDataRepository.resetPrivacyStats() }

    /** "Update blocklist now" (4.2): fetch → validate → activate, with a user-visible outcome. */
    fun updateBlocklistNow() {
        if (_uiState.value.refreshInProgress) return
        _uiState.value = _uiState.value.copy(refreshInProgress = true)
        launchSafely {
            val raw = blocklistFetcher.fetch()
            val message =
                if (raw == null) {
                    app.getString(R.string.settings_blocker_update_failed)
                } else if (blocklistStore.activate(raw)) {
                    cachedBlocklistHostCount = blocklistStore.activeBlocklist().size
                    _uiState.value = _uiState.value.copy(blocklistHostCount = cachedBlocklistHostCount)
                    app.getString(R.string.settings_blocker_updated_fmt, cachedBlocklistHostCount)
                } else {
                    app.getString(R.string.settings_blocker_update_failed)
                }
            _uiState.value =
                _uiState.value.copy(
                    refreshInProgress = false,
                    lastRefreshMessage = message,
                )
        }
    }

    // --- 4.x browser toggles ---

    fun setContentBlockerEnabled(enabled: Boolean) =
        launchSafely { settingsRepository.setContentBlockerEnabled(enabled) }

    fun setBackgroundAudioEnabled(enabled: Boolean) =
        launchSafely { settingsRepository.setBackgroundAudioEnabled(enabled) }

    fun setCloseIncognitoOnExit(enabled: Boolean) = launchSafely { settingsRepository.setCloseIncognitoOnExit(enabled) }

    fun setAutoCloseTabs(mode: TabAutoClose) = launchSafely { settingsRepository.setAutoCloseOldTabs(mode.id) }

    fun setHistoryLimit(limit: Int) = launchSafely { settingsRepository.setHistoryLimit(limit) }

    fun setAutoHideToolbar(enabled: Boolean) = launchSafely { settingsRepository.setAutoHideToolbar(enabled) }

    fun setAutoFailoverEnabled(enabled: Boolean) = launchSafely { settingsRepository.setAutoFailoverEnabled(enabled) }

    fun setBackgroundLatencyMeasureEnabled(enabled: Boolean) =
        launchSafely {
            settingsRepository.setBackgroundLatencyMeasureEnabled(enabled)
            if (enabled) {
                InstanceHealthWorker.schedule(app)
            } else {
                InstanceHealthWorker.cancel(app)
            }
        }

    // --- 3.2 user-defined tracking parameters ---

    fun setCustomTrackingParams(params: List<dev.libredirect.mobile.settings.CustomTrackingParam>) =
        launchSafely { settingsRepository.setCustomTrackingParams(params) }

    // --- 2.3 instance health panel ---

    /** On-demand probe of one frontend's instances ("Measure all"). */
    fun measureInstancesFor(frontendId: String) {
        val frontend =
            manifest?.routes?.flatMap { it.frontends }?.find { it.id == frontendId }
                ?.takeIf { it.instances.isNotEmpty() }
                ?: return
        if (_uiState.value.isMeasuringHealth) return
        _uiState.value = _uiState.value.copy(isMeasuringHealth = true)
        launchSafely {
            val probe = dev.libredirect.mobile.browser.InstanceProbe()
            val results = probe.measure(frontend.instances)
            browserDataRepository.saveLatencies(results)
            val health =
                (results.keys + browserDataRepository.instanceFailures().keys).associateWith { host ->
                    dev.libredirect.mobile.core.routing.InstanceHealth(
                        latencyMillis = results[host],
                        lastFailedAtMillis = browserDataRepository.instanceFailures()[host],
                    )
                }
            _uiState.value = _uiState.value.copy(instanceHealth = health, isMeasuringHealth = false)
        }
    }

    /** On-demand probe of all services' instances in the background with remembered results. */
    fun measureAllServicesNow() {
        if (_uiState.value.isMeasuringAllServices) return
        _uiState.value = _uiState.value.copy(isMeasuringAllServices = true, latencyMeasureMessage = null)
        launchSafely {
            val currentManifest = loadManifest() ?: run {
                _uiState.value = _uiState.value.copy(isMeasuringAllServices = false)
                return@launchSafely
            }
            val instances =
                withContext(Dispatchers.IO) {
                    currentManifest.routes
                        .flatMap { route -> route.frontends.flatMap { it.instances } }
                        .distinct()
                }
            if (instances.isEmpty()) {
                _uiState.value = _uiState.value.copy(isMeasuringAllServices = false)
                return@launchSafely
            }
            val probe = dev.libredirect.mobile.browser.InstanceProbe()
            val results = probe.measure(instances)
            browserDataRepository.saveLatencies(results)
            val now = System.currentTimeMillis()
            browserDataRepository.setLastLatencyMeasureResult(now, results.size)
            loadInstanceHealth()
            _uiState.value =
                _uiState.value.copy(
                    isMeasuringAllServices = false,
                    lastLatencyMeasureAt = now,
                    lastLatencyMeasureCount = results.size,
                    latencyMeasureMessage = app.getString(R.string.settings_latency_measured_fmt, results.size),
                )
        }
    }

    fun clearLatencyMeasurements() {
        launchSafely {
            browserDataRepository.clearLatencies()
            _uiState.value =
                _uiState.value.copy(
                    lastLatencyMeasureAt = 0L,
                    lastLatencyMeasureCount = 0,
                    instanceHealth = emptyMap(),
                    latencyMeasureMessage = app.getString(R.string.settings_latency_cleared),
                )
        }
    }

    /** Seeds the health panel from stored measurements when the detail screen opens. */
    fun loadInstanceHealth() {
        launchSafely {
            val latencies = browserDataRepository.cachedLatencies()
            val failures = browserDataRepository.instanceFailures()
            val health =
                (latencies.keys + failures.keys).associateWith { host ->
                    dev.libredirect.mobile.core.routing.InstanceHealth(
                        latencyMillis = latencies[host],
                        lastFailedAtMillis = failures[host],
                    )
                }
            _uiState.value = _uiState.value.copy(instanceHealth = health)
        }
    }

    // --- 5.1 backup / restore ---

    fun exportSettingsTo(uri: Uri) {
        launchSafely {
            val (verName, _, _) = versionInfo()
            val settings = settingsRepository.current()
            val backup =
                SettingsBackupCodec.Backup(
                    exportedAtEpochMillis = System.currentTimeMillis(),
                    appVersionName = verName,
                    settings = settings,
                    customRoutes = manifestRepository.customRoutes(),
                    bookmarks = browserDataRepository.bookmarks.first(),
                    siteSettingHosts = settings.siteSettings.keys.toList(),
                )
            val encoded = SettingsBackupCodec.encode(backup)
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(encoded.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("Cannot open export target")
            }
            _uiState.value = _uiState.value.copy(customServiceMessage = app.getString(R.string.settings_backup_created))
        }
    }

    /** Reads and validates a backup; the summary dialog applies it on confirm (5.1). */
    fun importSettingsFrom(uri: Uri) {
        launchSafely {
            val content =
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes()
                    } ?: throw IOException("Cannot open import source")
                }
            when (val result = SettingsBackupCodec.decode(String(content, Charsets.UTF_8))) {
                is SettingsBackupCodec.ImportResult.Success ->
                    _uiState.value = _uiState.value.copy(pendingSettingsImport = result.backup)
                is SettingsBackupCodec.ImportResult.Failure -> {
                    val reasonText =
                        when (result.reason) {
                            SettingsBackupCodec.Reason.MALFORMED -> R.string.backup_reason_malformed
                            SettingsBackupCodec.Reason.UNSUPPORTED_VERSION -> R.string.backup_reason_unsupported_version
                            SettingsBackupCodec.Reason.TOO_LARGE -> R.string.backup_reason_too_large
                            SettingsBackupCodec.Reason.INVALID_CUSTOM_INSTANCE ->
                                R.string.backup_reason_invalid_custom_instance
                        }
                    _uiState.value =
                        _uiState.value.copy(
                            errorMessage = app.getString(R.string.backup_import_failed_fmt, app.getString(reasonText)),
                        )
                }
            }
        }
    }

    fun applyPendingImport() {
        val backup = _uiState.value.pendingSettingsImport ?: return
        launchSafely {
            settingsRepository.importAll(backup.settings)
            for (route in backup.customRoutes) {
                manifestRepository.addCustomRoute(route)
            }
            if (backup.bookmarks.isNotEmpty()) {
                browserDataRepository.importBookmarks(backup.bookmarks)
            }
            _uiState.value =
                _uiState.value.copy(
                    pendingSettingsImport = null,
                    customServiceMessage = app.getString(R.string.backup_import_applied),
                )
        }
    }

    fun dismissPendingImport() {
        _uiState.value = _uiState.value.copy(pendingSettingsImport = null)
    }

    // --- 6 diagnostics ---

    /** Everything the diagnostics screen shows, as copyable text (no URLs/history). */
    fun diagnosticsText(): String {
        val manifest = this.manifest
        val routeCount = manifest?.routes?.size ?: 0
        val frontendCount = manifest?.routes?.sumOf { it.frontends.size } ?: 0
        val instanceCount = manifest?.routes?.sumOf { route -> route.frontends.sumOf { it.instances.size } } ?: 0
        val settings = _uiState.value
        val webviewVersion = webViewVersion()
        return buildString {
            appendLine("LibRedirect Mobile diagnostics")
            appendLine("${settings.appVersionDisplay}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("WebView: $webviewVersion")
            appendLine("Routing data revision: ${settings.manifestRevision ?: "n/a"}")
            appendLine("Rules: $routeCount services / $frontendCount frontends / $instanceCount instances")
            appendLine(
                "Settings: routing=${settings.routingEnabled} paused=${settings.pausedUntilEpochMillis > 0} " +
                    "browser=inApp(${settings.selectedBrowserPackage == BrowserLauncher.IN_APP_BROWSER_PACKAGE}) " +
                    "blocker=${settings.contentBlockerEnabled} unwrap=${settings.unwrapWrappersEnabled} " +
                    "shortLinks=${settings.shortLinkResolveEnabled} failover=${settings.autoFailoverEnabled} " +
                    "bgAudio=${settings.backgroundAudioEnabled} historyLimit=${settings.historyLimit}",
            )
            appendLine("Exceptions: ${settings.exceptions.size} rule(s)")
            val counters =
                "redirects=${settings.privacyStats.totalRedirects} " +
                    "stripped=${settings.privacyStats.strippedTrackingParams}"
            appendLine("Privacy counters: $counters")
            appendLine("--- recent errors ---")
            append(DiagnosticsLog.asText())
        }
    }

    private fun webViewVersion(): String =
        try {
            val info = app.packageManager.getPackageInfo("com.google.android.webview", 0)
            info.versionName ?: app.getString(R.string.diagnostics_webview_unknown)
        } catch (_: Exception) {
            app.getString(R.string.diagnostics_webview_unknown)
        }

    // --- 4.6 background audio hook ---

    /**
     * Called from MainActivity.onStop: when the user opted in and media is
     * playing on the active tab, a foreground service keeps playback alive.
     */
    fun maybeStartMediaPlaybackService() {
        if (!latestSettings.backgroundAudioEnabled) return
        if (!MediaPlaybackBus.state.value.isPlaying) return
        MediaPlaybackService.start(app)
    }

    fun maybeCloseIncognitoTabs(browserViewModel: BrowserViewModel) {
        if (latestSettings.closeIncognitoOnExit) browserViewModel.closeIncognitoTabs()
    }

    fun setSelectedBrowser(packageName: String?) = launchSafely { settingsRepository.setSelectedBrowser(packageName) }

    fun setSearchEngine(id: String) = launchSafely { settingsRepository.setSearchEngine(id) }

    fun setCustomSearchEngine(
        name: String,
        url: String,
    ) = launchSafely {
        settingsRepository.setCustomSearchEngine(name, url)
        settingsRepository.setSearchEngine(SearchEngine.CUSTOM_ID)
    }

    fun setSelectedFrontend(
        routeId: String,
        frontendId: String,
    ) = launchSafely { settingsRepository.setSelectedFrontend(routeId, frontendId) }

    fun setInstanceSelection(
        routeId: String,
        frontendId: String,
        selection: InstanceSelection,
    ) = launchSafely { settingsRepository.setInstanceSelection(routeId, frontendId, selection) }

    fun setExceptions(exceptions: List<ExceptionRule>) = launchSafely { settingsRepository.setExceptions(exceptions) }

    fun setClipboardOfferEnabled(enabled: Boolean) =
        launchSafely { settingsRepository.setClipboardOfferEnabled(enabled) }

    /**
     * Toggles the opt-in daily background instance refresh. Scheduling lives
     * here so the Worker itself stays framework-agnostic about settings.
     */
    fun setAutoInstanceRefreshEnabled(enabled: Boolean) =
        launchSafely {
            settingsRepository.setAutoInstanceRefreshEnabled(enabled)
            if (enabled) {
                InstanceRefreshWorker.schedule(app)
            } else {
                InstanceRefreshWorker.cancel(app)
            }
        }

    fun setBrowserTextZoom(percent: Int) = launchSafely { settingsRepository.setBrowserTextZoom(percent) }

    fun setAppLockEnabled(enabled: Boolean) = launchSafely { settingsRepository.setAppLockEnabled(enabled) }

    /**
     * Offers a home-screen shortcut for one service (C3): tapping it opens the
     * service's chosen instance in a new browser tab (handled by
     * [dev.libredirect.mobile.MainActivity] via [ShortcutActions.OPEN_SERVICE]).
     */
    fun pinServiceShortcut(routeId: String) {
        val context = app
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            _uiState.value = _uiState.value.copy(errorMessage = app.getString(R.string.shortcut_pin_unsupported))
            return
        }
        val route = manifest?.routes?.find { it.id == routeId } ?: return
        val shortcutIntent =
            Intent(context, MainActivity::class.java).apply {
                action = ShortcutActions.OPEN_SERVICE
                putExtra(ShortcutActions.EXTRA_ROUTE_ID, routeId)
            }
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, SERVICE_SHORTCUT_ID_PREFIX + routeId)
                .setShortLabel(route.name)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_routing))
                .setIntent(shortcutIntent)
                .build()
        val pinned = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        if (!pinned) {
            _uiState.value = _uiState.value.copy(errorMessage = app.getString(R.string.shortcut_pin_unsupported))
        }
    }

    /**
     * Called when the Home screen appears: if the user opted in, offers to
     * route a link found in the clipboard. Only the primary clip item is
     * read, once per appearance, and the same URL is never offered twice in
     * a process. Android 12+ shows a system "pasted" notice, which is why
     * the feature defaults to off.
     */
    fun checkClipboardForLinkOffer() {
        if (!latestSettings.clipboardOfferEnabled) return
        val clipboard = app.getSystemService(Application.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        val offerUrl = UrlCandidate.find(text)?.takeIf { it != lastClipboardOfferUrl } ?: return
        lastClipboardOfferUrl = offerUrl
        _uiState.value = _uiState.value.copy(clipboardLinkOffer = offerUrl)
    }

    fun dismissClipboardOffer() {
        _uiState.value = _uiState.value.copy(clipboardLinkOffer = null)
    }

    fun addCustomRoute(route: Route) {
        launchSafely {
            val result = withContext(Dispatchers.IO) { manifestRepository.addCustomRoute(route) }
            if (result.isSuccess) {
                customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
                manifest = loadManifest()
                val settings = settingsRepository.settings.first()
                _uiState.value =
                    buildUiState(settings).copy(
                        customServiceMessage = app.getString(R.string.custom_service_added),
                    )
            } else {
                _uiState.value =
                    _uiState.value.copy(
                        customServiceMessage =
                            result.exceptionOrNull()?.message
                                ?: app.getString(R.string.custom_service_add_failed),
                    )
            }
        }
    }

    fun removeCustomRoute(routeId: String) {
        launchSafely {
            withContext(Dispatchers.IO) { manifestRepository.removeCustomRoute(routeId) }
            customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
            manifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
            val settings = settingsRepository.settings.first()
            _uiState.value =
                buildUiState(settings).copy(
                    customServiceMessage = app.getString(R.string.custom_service_removed),
                )
        }
    }

    fun refreshManifest() {
        launchSafely {
            _uiState.value = _uiState.value.copy(refreshInProgress = true, lastRefreshMessage = null)

            val result = manifestRepository.refresh()
            settingsRepository.setManifestLastGenerationAt(System.currentTimeMillis())
            manifest = loadManifest()

            val message =
                when (result) {
                    is RefreshResult.Activated -> app.getString(R.string.refresh_updated_fmt, result.revision)
                    is RefreshResult.NotModified -> app.getString(R.string.refresh_up_to_date)
                    is RefreshResult.Rejected -> app.getString(R.string.refresh_failed_fmt, result.reason)
                }

            val settings = settingsRepository.settings.first()
            _uiState.value = buildUiState(settings).copy(refreshInProgress = false, lastRefreshMessage = message)
        }
    }

    private fun browserLabelFor(selectedBrowserPackage: String?): String =
        when (selectedBrowserPackage) {
            null, BrowserLauncher.SYSTEM_DEFAULT_PACKAGE -> app.getString(R.string.browser_picker_system_default)
            BrowserLauncher.IN_APP_BROWSER_PACKAGE -> app.getString(R.string.browser_in_app_label)
            else ->
                browsers.find { it.packageName == selectedBrowserPackage }?.label
                    ?: selectedBrowserPackage
        }

    private fun buildUiState(settings: AppSettings): MainUiState {
        val services = buildServiceStates(settings)

        val browserLabel = browserLabelFor(settings.selectedBrowserPackage)

        val (verName, verCode, verDisplay) = versionInfo()
        val currentManifest = manifest

        return MainUiState(
            loading = false,
            routingEnabled = settings.routingEnabled,
            selectedBrowserPackage = settings.selectedBrowserPackage,
            selectedBrowserLabel = browserLabel,
            services = services,
            manifestRevision = currentManifest?.revision,
            manifestErrorMessage = manifestErrorMessage,
            exceptions = settings.exceptions,
            refreshInProgress = _uiState.value.refreshInProgress,
            lastRefreshMessage = _uiState.value.lastRefreshMessage,
            customRoutes = customRoutes,
            customServiceMessage = _uiState.value.customServiceMessage,
            errorMessage = _uiState.value.errorMessage,
            updateAvailable = _uiState.value.updateAvailable,
            updateChecked = _uiState.value.updateChecked,
            updateCheckInProgress = _uiState.value.updateCheckInProgress,
            updateCheckFailed = _uiState.value.updateCheckFailed,
            appVersionName = verName,
            appVersionCode = verCode,
            appVersionDisplay = verDisplay,
            searchEngine = settings.searchEngine,
            customSearchEngineName = settings.customSearchEngineName,
            customSearchEngineUrl = settings.customSearchEngineUrl,
            clipboardOfferEnabled = settings.clipboardOfferEnabled,
            autoInstanceRefreshEnabled = settings.autoInstanceRefreshEnabled,
            browserTextZoom = settings.browserTextZoom,
            appLockEnabled = settings.appLockEnabled,
            askModeRoutes = settings.askModeRoutes,
            pausedUntilEpochMillis = settings.pausedUntilEpochMillis,
            privacyStats = _uiState.value.privacyStats,
            unwrapWrappersEnabled = settings.unwrapRedirectWrappersEnabled,
            shortLinkResolveEnabled = settings.shortLinkResolveEnabled,
            customTrackingParams = settings.customTrackingParams,
            contentBlockerEnabled = settings.contentBlockerEnabled,
            blocklistHostCount = cachedBlocklistHostCount,
            backgroundAudioEnabled = settings.backgroundAudioEnabled,
            closeIncognitoOnExit = settings.closeIncognitoOnExit,
            autoCloseTabs = TabAutoClose.fromId(settings.autoCloseOldTabs),
            historyLimit = settings.historyLimit,
            autoHideToolbar = settings.autoHideToolbar,
            autoFailoverEnabled = settings.autoFailoverEnabled,
            backgroundLatencyMeasureEnabled = settings.backgroundLatencyMeasureEnabled,
            isMeasuringAllServices = _uiState.value.isMeasuringAllServices,
            lastLatencyMeasureAt = _uiState.value.lastLatencyMeasureAt,
            lastLatencyMeasureCount = _uiState.value.lastLatencyMeasureCount,
            latencyMeasureMessage = _uiState.value.latencyMeasureMessage,
        )
    }

    private fun buildServiceStates(settings: AppSettings): List<ServiceUiState> =
        manifest?.routes?.map { route ->
            ServiceUiState(
                routeId = route.id,
                name = route.name,
                enabled = settings.isRouteEnabled(route.id),
                frontends =
                    route.frontends.map { frontend ->
                        FrontendUiState(
                            id = frontend.id,
                            name = frontend.name,
                            needsInstance = frontend.instances.isNotEmpty(),
                            instances = frontend.instances,
                            selection =
                                settings.instanceSelections["${route.id}/${frontend.id}"]
                                    ?: InstanceSelection.Automatic,
                        )
                    },
                selectedFrontendId = settings.selectedFrontends[route.id] ?: route.frontends.first().id,
                askModeRoutes = settings.askModeRoutes,
            )
        } ?: emptyList()

    private suspend fun loadManifest(): Manifest? =
        withContext(Dispatchers.IO) {
            val loaded = manifestRepository.activeManifest()
            manifestErrorMessage =
                if (loaded == null) {
                    manifestRepository.lastLoadError() ?: app.getString(R.string.routing_data_unavailable)
                } else {
                    null
                }
            loaded
        }

    @Suppress("TooGenericExceptionCaught")
    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unexpected error", error)
                DiagnosticsLog.record(TAG, error.message ?: "unexpected error")
                _uiState.value =
                    _uiState.value.copy(
                        errorMessage = error.message ?: app.getString(R.string.generic_error),
                    )
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val MILLIS_PER_MINUTE = 60_000L

        /** Minimum gap between automatic startup manifest generations. */
        const val SILENT_GENERATION_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val SERVICE_SHORTCUT_ID_PREFIX = "service_"

        /** Sentinel "until I turn it back on" pause timestamp (2.6). */
        const val PAUSE_INDEFINITE = Long.MAX_VALUE / 4

        /** Well-known service apps probed during onboarding step 3 (5.2). */
    }
}
