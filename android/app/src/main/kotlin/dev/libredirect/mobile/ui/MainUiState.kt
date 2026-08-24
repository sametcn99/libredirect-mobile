package dev.libredirect.mobile.ui

import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.update.UpdateInfo

data class MainUiState(
    val loading: Boolean = true,
    val routingEnabled: Boolean = true,
    val selectedBrowserPackage: String? = null,
    val selectedBrowserLabel: String = "System default",
    val services: List<ServiceUiState> = emptyList(),
    val manifestRevision: Int? = null,
    val exceptions: List<ExceptionRule> = emptyList(),
    val refreshInProgress: Boolean = false,
    val lastRefreshMessage: String? = null,
    val customRoutes: List<Route> = emptyList(),
    val customServiceMessage: String? = null,
    val errorMessage: String? = null,
    val updateAvailable: UpdateInfo? = null,
)

data class ServiceUiState(
    val routeId: String,
    val name: String,
    val enabled: Boolean,
    val frontends: List<FrontendUiState>,
    val selectedFrontendId: String,
)

data class FrontendUiState(
    val id: String,
    val name: String,
    val needsInstance: Boolean,
    val instances: List<String>,
    val selection: InstanceSelection,
)
