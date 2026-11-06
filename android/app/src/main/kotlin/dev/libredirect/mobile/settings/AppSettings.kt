package dev.libredirect.mobile.settings

import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.routing.RoutingContext

data class AppSettings(
    val routingEnabled: Boolean = true,
    val selectedBrowserPackage: String? = BrowserLauncher.IN_APP_BROWSER_PACKAGE,
    /** Route ids the user has turned off; a route not in this set is enabled by default. */
    val disabledRoutes: Set<String> = emptySet(),
    /** routeId -> frontendId. Missing entry defaults to the route's first frontend. */
    val selectedFrontends: Map<String, String> = emptyMap(),
    /** "$routeId/$frontendId" -> selection. Missing entry defaults to Automatic. */
    val instanceSelections: Map<String, InstanceSelection> = emptyMap(),
    val exceptions: List<ExceptionRule> = emptyList(),
    val searchEngine: String = "duckduckgo",
    /** Custom search engine; an empty URL means none is configured. */
    val customSearchEngineName: String = "",
    val customSearchEngineUrl: String = "",
    /** In-app browser page color scheme: "system", "light" or "dark". */
    val browserPageTheme: String = "system",
    /** Darken pages that ship no dark theme of their own while the page theme is dark. */
    val browserAutoDarken: Boolean = true,
    /** Offer to route a link found in the clipboard on the Home screen. Opt-in (Android 12+ shows a paste notice). */
    val clipboardOfferEnabled: Boolean = false,
    /** Daily background refresh of frontend instance lists via WorkManager. Opt-in, off by default. */
    val autoInstanceRefreshEnabled: Boolean = false,
    /** Page text scaling in percent (100 = default). Applied to every WebView. */
    val browserTextZoom: Int = 100,
    /** Require biometric/device-credential unlock when the app comes to the foreground. */
    val appLockEnabled: Boolean = false,
) {
    fun isRouteEnabled(routeId: String): Boolean = routeId !in disabledRoutes

    fun routingContext(): RoutingContext =
        RoutingContext(
            exceptions = exceptions,
            disabledRoutes = disabledRoutes,
            selectedFrontends = selectedFrontends,
            instanceSelections = instanceSelections,
        )
}
