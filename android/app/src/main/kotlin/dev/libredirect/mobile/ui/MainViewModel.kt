package dev.libredirect.mobile.ui

import android.app.Application
import android.content.ClipboardManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.libredirect.mobile.BuildConfig
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserInfo
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.url.UrlCandidate
import dev.libredirect.mobile.manifest.InstanceRefreshWorker
import dev.libredirect.mobile.manifest.RefreshResult
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
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

@Suppress("TooManyFunctions")
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<LibRedirectApplication>()
    private val settingsRepository = SettingsRepository(app)
    private val manifestRepository = app.manifestRepository
    private val browserLauncher = BrowserLauncher(app)
    private val updateRepository = UpdateRepository(settingsRepository)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

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

    fun setSelectedBrowser(packageName: String?) = launchSafely { settingsRepository.setSelectedBrowser(packageName) }

    fun setSearchEngine(id: String) = launchSafely { settingsRepository.setSearchEngine(id) }

    fun setCustomSearchEngine(
        name: String,
        url: String,
    ) = launchSafely {
        settingsRepository.setCustomSearchEngine(name, url)
        settingsRepository.setSearchEngine(SearchEngine.CUSTOM_ID)
    }

    fun setRouteEnabled(
        routeId: String,
        enabled: Boolean,
    ) = launchSafely { settingsRepository.setRouteEnabled(routeId, enabled) }

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
        val currentManifest = manifest
        val services =
            currentManifest?.routes?.map { route ->
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
                )
            } ?: emptyList()

        val browserLabel = browserLabelFor(settings.selectedBrowserPackage)

        val (verName, verCode, verDisplay) = versionInfo()

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
        )
    }

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
                _uiState.value =
                    _uiState.value.copy(
                        errorMessage = error.message ?: app.getString(R.string.generic_error),
                    )
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"

        /** Minimum gap between automatic startup manifest generations. */
        const val SILENT_GENERATION_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
