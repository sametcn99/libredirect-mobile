package dev.libredirect.mobile.ui

import dev.libredirect.mobile.BuildConfig
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceHealth
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.settings.CustomTrackingParam
import dev.libredirect.mobile.settings.RouteMode
import dev.libredirect.mobile.settings.SettingsBackupCodec
import dev.libredirect.mobile.settings.TabAutoClose
import dev.libredirect.mobile.ui.browser.PrivacyStats
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
    // True once a check against GitHub Releases has actually succeeded.
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
    // A routable URL found in the clipboard and offered on Home; null hides the card.
    val clipboardLinkOffer: String? = null,
    val clipboardOfferEnabled: Boolean = false,
    val autoInstanceRefreshEnabled: Boolean = false,
    // Page text scaling in percent (100 = default).
    val browserTextZoom: Int = 100,
    // Require biometric/device-credential unlock in the foreground.
    val appLockEnabled: Boolean = false,
    // Notes from the last manifest generation: targets/frontends the converter dropped.
    val generationNotes: List<String> = emptyList(),
    // Local redirect counters per routeId (never leaves the device).
    val redirectStats: Map<String, Int> = emptyMap(),
    // Route ids in "ask every time" mode (2.4).
    val askModeRoutes: Set<String> = emptySet(),
    // Epoch millis routing is paused until (2.6); 0 = running.
    val pausedUntilEpochMillis: Long = 0L,
    // Local privacy counters shown on Home (3.4).
    val privacyStats: PrivacyStats = PrivacyStats(),
    val unwrapWrappersEnabled: Boolean = true,
    val shortLinkResolveEnabled: Boolean = false,
    // User-defined tracking parameter rules (3.2).
    val customTrackingParams: List<CustomTrackingParam> = emptyList(),
    val contentBlockerEnabled: Boolean = true,
    val blocklistHostCount: Int = 0,
    val backgroundAudioEnabled: Boolean = false,
    val closeIncognitoOnExit: Boolean = false,
    val autoCloseTabs: TabAutoClose = TabAutoClose.NEVER,
    val historyLimit: Int = 200,
    val autoHideToolbar: Boolean = false,
    val autoFailoverEnabled: Boolean = true,
    val backgroundLatencyMeasureEnabled: Boolean = false,
    // A validated backup awaiting the confirm dialog (5.1); null hides it.
    val pendingSettingsImport: SettingsBackupCodec.Backup? = null,
    // Latency + cooldown data for the service-detail health panel (2.3).
    val instanceHealth: Map<String, InstanceHealth> = emptyMap(),
    val isMeasuringHealth: Boolean = false,
    // Background and manual instance latency measurement state
    val isMeasuringAllServices: Boolean = false,
    val lastLatencyMeasureAt: Long = 0L,
    val lastLatencyMeasureCount: Int = 0,
    val latencyMeasureMessage: String? = null,
)

data class ServiceUiState(
    val routeId: String,
    val name: String,
    val enabled: Boolean,
    val frontends: List<FrontendUiState>,
    val selectedFrontendId: String,
    // Route ids in "ask every time" mode; carried in for the three-state UI (2.4).
    val askModeRoutes: Set<String> = emptySet(),
) {
    // The three-state routing mode this service is in (2.4).
    val routeMode: RouteMode
        get() =
            when {
                !enabled -> RouteMode.OFF
                routeId in askModeRoutes -> RouteMode.ASK
                else -> RouteMode.ALWAYS
            }
}

data class FrontendUiState(
    val id: String,
    val name: String,
    val needsInstance: Boolean,
    val instances: List<String>,
    val selection: InstanceSelection,
)
