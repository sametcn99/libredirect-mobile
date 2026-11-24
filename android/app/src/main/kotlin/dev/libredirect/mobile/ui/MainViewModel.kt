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
import androidx.webkit.WebViewCompat
import dev.libredirect.mobile.BuildConfig
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.MainActivity
import dev.libredirect.mobile.R
import dev.libredirect.mobile.blocker.BlocklistFetcher
import dev.libredirect.mobile.blocker.BlocklistStore
import dev.libredirect.mobile.browser.BrowserInfo
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.browser.InstanceProbe
import dev.libredirect.mobile.browser.ShortcutActions
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.diagnostics.DiagnosticsLog
import dev.libredirect.mobile.diagnostics.RouteMeasurements
import dev.libredirect.mobile.diagnostics.RoutingOutcomeLog
import dev.libredirect.mobile.manifest.InstanceHealthWorker
import dev.libredirect.mobile.manifest.InstanceRefreshWorker
import dev.libredirect.mobile.manifest.RefreshResult
import dev.libredirect.mobile.media.MediaPlaybackBus
import dev.libredirect.mobile.media.MediaPlaybackService
import dev.libredirect.mobile.redirect.LinkInspection
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.BackupOptions
import dev.libredirect.mobile.settings.CustomTrackingParam
import dev.libredirect.mobile.settings.HostLinkPolicy
import dev.libredirect.mobile.settings.RouteLinkPolicy
import dev.libredirect.mobile.settings.RouteMode
import dev.libredirect.mobile.settings.RulePackage
import dev.libredirect.mobile.settings.SettingsBackupCodec
import dev.libredirect.mobile.settings.SettingsRepository
import dev.libredirect.mobile.settings.TabAutoClose
import dev.libredirect.mobile.ui.browser.BrowserDataRepository
import dev.libredirect.mobile.ui.browser.BrowserViewModel
import dev.libredirect.mobile.ui.browser.SearchEngine
import dev.libredirect.mobile.ui.browser.WebViewProfileManager
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
import java.time.Instant

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
    private val _inspectionText = MutableStateFlow("")
    val inspectionText = _inspectionText.asStateFlow()

    fun inspectLinks(text: String) {
        _inspectionText.value = text
        onShortcutRoute(ROUTE_INSPECT_LINK)
    }

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

    fun setQueryParamStripEnabled(enabled: Boolean) =
        launchSafely { settingsRepository.setQueryParamStripEnabled(enabled) }

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

    fun setAskBeforeDownload(enabled: Boolean) = launchSafely { settingsRepository.setAskBeforeDownload(enabled) }

    fun setDownloadWifiOnly(enabled: Boolean) = launchSafely { settingsRepository.setDownloadWifiOnly(enabled) }

    fun setClearIncognitoDownloads(enabled: Boolean) =
        launchSafely { settingsRepository.setClearIncognitoDownloads(enabled) }

    fun setCloseIncognitoOnExit(enabled: Boolean) = launchSafely { settingsRepository.setCloseIncognitoOnExit(enabled) }

    fun setAutoCloseTabs(mode: TabAutoClose) = launchSafely { settingsRepository.setAutoCloseOldTabs(mode.id) }

    fun setHistoryLimit(limit: Int) =
        launchSafely {
            settingsRepository.setHistoryLimit(limit)
            browserDataRepository.enforceHistoryLimit(settingsRepository.current().historyLimit)
        }

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

    fun setCustomTrackingParams(params: List<CustomTrackingParam>) =
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
            val probe = InstanceProbe()
            val results = probe.measure(frontend.instances)
            browserDataRepository.saveInstanceMeasurements(results)
            val health = browserDataRepository.instanceHealth()
            _uiState.value = _uiState.value.copy(instanceHealth = health, isMeasuringHealth = false)
        }
    }

    /** On-demand probe of all services' instances in the background with remembered results. */
    fun measureAllServicesNow() {
        if (_uiState.value.isMeasuringAllServices) return
        _uiState.value = _uiState.value.copy(isMeasuringAllServices = true, latencyMeasureMessage = null)
        launchSafely {
            val currentManifest =
                loadManifest() ?: run {
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
            val probe = InstanceProbe()
            val results = probe.measure(instances)
            browserDataRepository.saveInstanceMeasurements(results)
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
            val health = browserDataRepository.instanceHealth()
            _uiState.value = _uiState.value.copy(instanceHealth = health)
        }
    }

    // --- 5.1 backup / restore ---

    fun exportSettingsTo(
        uri: Uri,
        options: BackupOptions,
    ) {
        launchSafely {
            val (verName, _, _) = versionInfo()
            val settings = settingsRepository.current()
            val backup =
                SettingsBackupCodec.Backup(
                    exportedAtEpochMillis = System.currentTimeMillis(),
                    appVersionName = verName,
                    settings = if (options.settings) settings else AppSettings(),
                    customRoutes =
                        manifestRepository.customRoutes().filter { route ->
                            options.customRoutes && (options.serviceIds == null || route.id in options.serviceIds)
                        },
                    bookmarks = if (options.bookmarks) browserDataRepository.bookmarks.first() else emptyList(),
                    siteSettingHosts = if (options.settings) settings.siteSettings.keys.toList() else emptyList(),
                    includesSettings = options.settings,
                    sharedRules =
                        options.serviceIds?.let {
                            RulePackage.fromSettings(settings, it)
                        },
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
                        val limit = SettingsBackupCodec.MAX_BACKUP_BYTES + 1
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(BACKUP_READ_BUFFER_BYTES)
                        while (output.size() < limit) {
                            val read = stream.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    } ?: throw IOException("Cannot open import source")
                }
            when (val result = SettingsBackupCodec.decode(String(content, Charsets.UTF_8))) {
                is SettingsBackupCodec.ImportResult.Success -> {
                    val items =
                        withContext(Dispatchers.IO) { manifestRepository.previewRuleImport(result.backup.customRoutes) }
                    _uiState.value = _uiState.value.copy(
                        pendingSettingsImport = result.backup,
                        pendingRuleItems = items,
                    )
                }
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

    fun applyPendingImport(replaceConflicts: Boolean) {
        val backup = _uiState.value.pendingSettingsImport ?: return
        _uiState.value = _uiState.value.copy(pendingSettingsImport = null)
        launchSafely {
            if (backup.includesSettings) {
                settingsRepository.importAll(backup.settings)
                browserDataRepository.enforceHistoryLimit(backup.settings.historyLimit)
            }
            backup.sharedRules?.let { rules -> settingsRepository.importSharedRules(rules, replaceConflicts) }
            val failures =
                withContext(Dispatchers.IO) {
                    manifestRepository.previewRuleImport(backup.customRoutes).mapNotNull { item ->
                        when {
                            item.conflict && !replaceConflicts -> null
                            item.error != null -> "${item.route.name} — ${item.error}"
                            else ->
                                manifestRepository.addCustomRoute(item.route).exceptionOrNull()?.let {
                                    "${item.route.name} — ${it.message.orEmpty()}"
                                }
                        }
                    }
                }
            if (backup.bookmarks.isNotEmpty()) browserDataRepository.importBookmarks(backup.bookmarks)
            _uiState.value =
                _uiState.value.copy(
                    pendingRuleItems = emptyList(),
                    customServiceMessage = app.getString(R.string.backup_import_applied).takeIf { failures.isEmpty() },
                    errorMessage =
                        failures.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
                            ?.let { app.getString(R.string.backup_import_partial, it) },
                )
        }
    }

    fun dismissPendingImport() {
        _uiState.value = _uiState.value.copy(pendingSettingsImport = null, pendingRuleItems = emptyList())
    }

    // --- 6 diagnostics ---

    suspend fun inspectLink(input: String): LinkInspection =
        dev.libredirect.mobile.redirect.inspectLink(
            input,
            settingsRepository.current(),
            withContext(Dispatchers.IO) { manifestRepository.activeManifest() },
            browserDataRepository,
        )

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
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("App start: ${Instant.ofEpochMilli(DiagnosticsLog.startedAtEpochMillis)}")
            appendLine("WebView: $webviewVersion")
            appendLine(RouteMeasurements.summary())
            appendLine("WebView isolated profiles: ${WebViewProfileManager.isSupported()}")
            appendLine("Routing data revision: ${settings.manifestRevision ?: "n/a"}")
            appendLine("Rules: $routeCount services / $frontendCount frontends / $instanceCount instances")
            appendLine(
                "Settings: routing=${settings.routingEnabled} paused=${settings.pausedUntilEpochMillis > 0} " +
                    "browser=inApp(${settings.selectedBrowserPackage == BrowserLauncher.IN_APP_BROWSER_PACKAGE}) " +
                    "blocker=${settings.contentBlockerEnabled} unwrap=${settings.unwrapWrappersEnabled} " +
                    "shortLinks=${settings.shortLinkResolveEnabled} stripQuery=${settings.queryParamStripEnabled} " +
                    "failover=${settings.autoFailoverEnabled} " +
                    "bgAudio=${settings.backgroundAudioEnabled} historyLimit=${settings.historyLimit}",
            )
            appendLine("Exceptions: ${settings.exceptions.size} rule(s)")
            val counters =
                "redirects=${settings.privacyStats.totalRedirects} " +
                    "stripped=${settings.privacyStats.strippedTrackingParams}"
            appendLine("Privacy counters: $counters")
            appendLine("--- recent routing outcomes (host only, this session) ---")
            appendLine(RoutingOutcomeLog.asText())
            appendLine("--- recent errors (persisted across restarts) ---")
            append(DiagnosticsLog.asText())
        }
    }

    private fun webViewVersion(): String =
        try {
            WebViewCompat.getCurrentWebViewPackage(app)?.let { "${it.packageName} ${it.versionName}" }
                ?: app.getString(R.string.diagnostics_webview_unknown)
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

    fun applyRoutingPreset(preset: dev.libredirect.mobile.settings.RoutingPreset) =
        launchSafely { settingsRepository.applyRoutingPreset(preset) }

    fun updateServiceLinkPolicy(
        routeId: String,
        update: (RouteLinkPolicy) -> RouteLinkPolicy,
    ) = launchSafely {
        settingsRepository.updateLinkPolicies { policies ->
            val previous = policies.routes[routeId] ?: RouteLinkPolicy(fallback = policies.defaultFallback)
            policies.copy(routes = policies.routes + (routeId to update(previous)))
        }
    }

    fun updateHostLinkPolicy(
        host: String,
        policy: HostLinkPolicy,
    ) = launchSafely {
        settingsRepository.updateLinkPolicies { policies ->
            val hosts =
                if (policy == HostLinkPolicy()) {
                    policies.hosts - host
                } else {
                    policies.hosts + (host to policy)
                }
            policies.copy(hosts = hosts)
        }
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
            linkPolicies = settings.linkPolicies,
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
            queryParamStripEnabled = settings.queryParamStripEnabled,
            customTrackingParams = settings.customTrackingParams,
            contentBlockerEnabled = settings.contentBlockerEnabled,
            blocklistHostCount = cachedBlocklistHostCount,
            backgroundAudioEnabled = settings.backgroundAudioEnabled,
            askBeforeDownload = settings.askBeforeDownload,
            downloadWifiOnly = settings.downloadWifiOnly,
            clearIncognitoDownloads = settings.clearIncognitoDownloads,
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
                sampleHost = route.hosts.firstOrNull(),
                name = route.name,
                enabled = settings.isRouteEnabled(route.id),
                frontends =
                    route.frontends.map { frontend ->
                        FrontendUiState(
                            id = frontend.id,
                            name = frontend.name,
                            needsInstance = Strategy.requiresInstances(frontend.strategy),
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
                DiagnosticsLog.record(TAG, error, "unexpected error")
                _uiState.value =
                    _uiState.value.copy(
                        errorMessage = error.message ?: app.getString(R.string.generic_error),
                    )
            }
        }
    }

    private companion object {
        const val BACKUP_READ_BUFFER_BYTES = 8192
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
