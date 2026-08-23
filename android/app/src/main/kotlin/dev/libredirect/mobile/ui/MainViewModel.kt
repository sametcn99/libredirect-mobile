package dev.libredirect.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.browser.BrowserInfo
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.manifest.ManifestEndpoints
import dev.libredirect.mobile.manifest.RefreshResult
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
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

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var manifest: Manifest? = null
    private var browsers: List<BrowserInfo> = emptyList()
    private var customRoutes: List<Route> = emptyList()

    init {
        viewModelScope.launch {
            manifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
            customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
            browsers = withContext(Dispatchers.IO) { browserLauncher.installedBrowsers() }
            settingsRepository.settings.collect { settings ->
                _uiState.value = buildUiState(settings)
            }
        }
    }

    fun installedBrowsers(): List<BrowserInfo> = browsers

    fun setRoutingEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setRoutingEnabled(enabled) }

    fun setSelectedBrowser(packageName: String?) =
        viewModelScope.launch { settingsRepository.setSelectedBrowser(packageName) }

    fun setRouteEnabled(
        routeId: String,
        enabled: Boolean,
    ) = viewModelScope.launch { settingsRepository.setRouteEnabled(routeId, enabled) }

    fun setSelectedFrontend(
        routeId: String,
        frontendId: String,
    ) = viewModelScope.launch { settingsRepository.setSelectedFrontend(routeId, frontendId) }

    fun setInstanceSelection(
        routeId: String,
        frontendId: String,
        selection: InstanceSelection,
    ) = viewModelScope.launch { settingsRepository.setInstanceSelection(routeId, frontendId, selection) }

    fun setExceptions(exceptions: List<ExceptionRule>) =
        viewModelScope.launch { settingsRepository.setExceptions(exceptions) }

    fun addCustomRoute(route: Route) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) { manifestRepository.removeCustomRoute(routeId) }
            customRoutes = withContext(Dispatchers.IO) { manifestRepository.customRoutes() }
            manifest = withContext(Dispatchers.IO) { manifestRepository.activeManifest() }
            _uiState.value = buildUiState(settingsRepository.settings.first()).copy(customServiceMessage = "Custom service removed")
        }
    }

    fun refreshManifest() {
        viewModelScope.launch {
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
        )
    }
}
