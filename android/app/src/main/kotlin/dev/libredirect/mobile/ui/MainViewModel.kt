package dev.libredirect.mobile.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.libredirect.mobile.LibRedirectApplication
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<LibRedirectApplication>()
    private val settingsRepository = SettingsRepository(app)
    private val manifestRepository = app.manifestRepository
    private val browserLauncher = BrowserLauncher(app.packageManager, app.packageName)
    private val updateChecker = UpdateChecker()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var manifest: Manifest? = null
    private var browsers: List<BrowserInfo> = emptyList()
    private var customRoutes: List<Route> = emptyList()

    init {
        launchSafely {
            manifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
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

    private fun currentVersionName(): String =
        try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            ""
        }

    fun installedBrowsers(): List<BrowserInfo> = browsers

    fun errorShown() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun setRoutingEnabled(enabled: Boolean) =
        launchSafely { settingsRepository.setRoutingEnabled(enabled) }

    fun setSelectedBrowser(packageName: String?) =
        launchSafely { settingsRepository.setSelectedBrowser(packageName) }

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

    fun setExceptions(exceptions: List<ExceptionRule>) =
        launchSafely { settingsRepository.setExceptions(exceptions) }

    fun addCustomRoute(route: Route) {
        launchSafely {
            val result = withContext(Dispatchers.IO) { manifestRepository.addCustomRoute(route) }
            if (result.isSuccess) {
                customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
                manifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
                _uiState.value = buildUiState(settingsRepository.settings.first()).copy(customServiceMessage = "Custom service added")
            } else {
                _uiState.value = _uiState.value.copy(
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
            _uiState.value = buildUiState(settingsRepository.settings.first()).copy(customServiceMessage = "Custom service removed")
        }
    }

    fun refreshManifest() {
        launchSafely {
            _uiState.value = _uiState.value.copy(refreshInProgress = true, lastRefreshMessage = null)

            val result = manifestRepository.refresh(ManifestEndpoints.MANIFEST_URL, ManifestEndpoints.SIGNATURE_URL)
            manifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }

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
            settings.selectedBrowserPackage
                ?.let { pkg -> browsers.find { it.packageName == pkg }?.label ?: pkg }
                ?: "System default"

        return MainUiState(
            loading = false,
            routingEnabled = settings.routingEnabled,
            selectedBrowserPackage = settings.selectedBrowserPackage,
            selectedBrowserLabel = browserLabel,
            services = services,
            manifestRevision = currentManifest?.revision,
            exceptions = settings.exceptions,
            refreshInProgress = _uiState.value.refreshInProgress,
            lastRefreshMessage = _uiState.value.lastRefreshMessage,
            customRoutes = customRoutes,
            customServiceMessage = _uiState.value.customServiceMessage,
            errorMessage = _uiState.value.errorMessage,
            updateAvailable = _uiState.value.updateAvailable,
        )
    }

    /**
     * Every ViewModel-initiated coroutine goes through here: an unexpected
     * exception (anything not already turned into a [Result] or sealed
     * result type by the repository layer) is logged and surfaced as
     * [MainUiState.errorMessage] instead of crashing the app.
     */
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
    }
}
