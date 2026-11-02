package dev.libredirect.mobile.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserInfo
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.version.AppVersion
import dev.libredirect.mobile.manifest.ManifestEndpoints
import dev.libredirect.mobile.manifest.RefreshResult
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
import dev.libredirect.mobile.update.UpdateChecker
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
    private val updateChecker = UpdateChecker()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var manifest: Manifest? = null
    private var manifestErrorMessage: String? = null
    private var browsers: List<BrowserInfo> = emptyList()
    private var customRoutes: List<Route> = emptyList()

    init {
        launchSafely {
            manifest = loadManifest()
            customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
            browsers = withContext(Dispatchers.IO) { browserLauncher.installedBrowsers() }
            settingsRepository.settings.collect { settings ->
                _uiState.value = buildUiState(settings)
            }
        }
        launchSafely {
            val latest = updateChecker.latestRelease()
            if (latest != null && AppVersion.isNewer(latest.versionName, currentVersionName())) {
                _uiState.value = _uiState.value.copy(updateAvailable = latest)
            }
        }
    }

    private fun versionInfo(): Triple<String, Long, String> =
        try {
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            val name = packageInfo.versionName ?: FALLBACK_VERSION_NAME
            val code =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            Triple(name, code, "v$name (Build $code)")
        } catch (_: Exception) {
            Triple(FALLBACK_VERSION_NAME, FALLBACK_VERSION_CODE, FALLBACK_VERSION_DISPLAY)
        }

    private fun currentVersionName(): String = versionInfo().first

    fun installedBrowsers(): List<BrowserInfo> = browsers

    fun errorShown() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun setRoutingEnabled(enabled: Boolean) = launchSafely { settingsRepository.setRoutingEnabled(enabled) }

    fun setSelectedBrowser(packageName: String?) = launchSafely { settingsRepository.setSelectedBrowser(packageName) }

    fun setSearchEngine(id: String) = launchSafely { settingsRepository.setSearchEngine(id) }

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

    fun addCustomRoute(route: Route) {
        launchSafely {
            val result = withContext(Dispatchers.IO) { manifestRepository.addCustomRoute(route) }
            if (result.isSuccess) {
                customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
                manifest = loadManifest()
                val settings = settingsRepository.settings.first()
                _uiState.value = buildUiState(settings).copy(customServiceMessage = "Custom service added")
            } else {
                _uiState.value =
                    _uiState.value.copy(
                        customServiceMessage = result.exceptionOrNull()?.message ?: "Could not add custom service",
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
            _uiState.value = buildUiState(settings).copy(customServiceMessage = "Custom service removed")
        }
    }

    fun refreshManifest() {
        launchSafely {
            _uiState.value = _uiState.value.copy(refreshInProgress = true, lastRefreshMessage = null)

            val result = manifestRepository.refresh(ManifestEndpoints.INSTANCES_DATA_URL)
            manifest = loadManifest()

            val message =
                when (result) {
                    is RefreshResult.Activated -> "Updated to revision ${result.revision}"
                    is RefreshResult.NotModified -> "Already up to date"
                    is RefreshResult.Rejected -> "Update failed: ${result.reason}"
                }

            val settings = settingsRepository.settings.first()
            _uiState.value = buildUiState(settings).copy(refreshInProgress = false, lastRefreshMessage = message)
        }
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

        val browserLabel =
            when (settings.selectedBrowserPackage) {
                null, BrowserLauncher.SYSTEM_DEFAULT_PACKAGE -> app.getString(R.string.browser_picker_system_default)
                BrowserLauncher.IN_APP_BROWSER_PACKAGE -> app.getString(R.string.browser_in_app_label)
                else ->
                    browsers.find { it.packageName == settings.selectedBrowserPackage }?.label
                        ?: settings.selectedBrowserPackage
            }

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
            appVersionName = verName,
            appVersionCode = verCode,
            appVersionDisplay = verDisplay,
            searchEngine = settings.searchEngine,
        )
    }

    private suspend fun loadManifest(): Manifest? =
        withContext(Dispatchers.IO) {
            val loaded = manifestRepository.activeManifest()
            manifestErrorMessage =
                if (loaded == null) {
                    manifestRepository.lastLoadError() ?: "Routing data is unavailable"
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
                _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "Something went wrong")
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val FALLBACK_VERSION_NAME = "1.1.0"
        const val FALLBACK_VERSION_CODE = 3L
        const val FALLBACK_VERSION_DISPLAY = "v1.1.0 (Build 3)"
    }
}
