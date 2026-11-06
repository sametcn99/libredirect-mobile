package dev.libredirect.mobile.ui

import dev.libredirect.mobile.BuildConfig
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.update.UpdateInfo

data class MainUiState(
    val loading: Boolean = true,
    val routingEnabled: Boolean = true,
    val selectedBrowserPackage: String? = BrowserLauncher.IN_APP_BROWSER_PACKAGE,
    val selectedBrowserLabel: String = "LibRedirect Browser",
    val services: List<ServiceUiState> = emptyList(),
    val manifestRevision: Int? = null,
    val manifestErrorMessage: String? = null,
    val exceptions: List<ExceptionRule> = emptyList(),
    val refreshInProgress: Boolean = false,
    val lastRefreshMessage: String? = null,
    val customRoutes: List<Route> = emptyList(),
    val customServiceMessage: String? = null,
    val errorMessage: String? = null,
    val updateAvailable: UpdateInfo? = null,
    /** True once a check against GitHub Releases has actually succeeded. */
    val updateChecked: Boolean = false,
    val updateCheckInProgress: Boolean = false,
    val updateCheckFailed: Boolean = false,
    // Kept in sync at compile time instead of a hand-edited fallback (see CONTRIBUTING's release checklist).
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    val appVersionDisplay: String = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
    val searchEngine: String = "duckduckgo",
    val customSearchEngineName: String = "",
    val customSearchEngineUrl: String = "",
    /** A routable URL found in the clipboard and offered on Home; null hides the card. */
    val clipboardLinkOffer: String? = null,
    val clipboardOfferEnabled: Boolean = false,
    val autoInstanceRefreshEnabled: Boolean = false,
    /** Page text scaling in percent (100 = default). */
    val browserTextZoom: Int = 100,
    /** Require biometric/device-credential unlock in the foreground. */
    val appLockEnabled: Boolean = false,
    /** Notes from the last manifest generation: targets/frontends the converter dropped. */
    val generationNotes: List<String> = emptyList(),
    /** Local redirect counters per routeId (never leaves the device). */
    val redirectStats: Map<String, Int> = emptyMap(),
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
