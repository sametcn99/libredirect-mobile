package dev.libredirect.mobile.settings

import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.routing.CustomTrackingRules
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.routing.RoutingContext
import kotlinx.serialization.Serializable

/**
 * One user-defined tracking parameter rule (3.2): a parameter name, either
 * global or scoped to a host (and its subdomains). A name ending in `*` is a
 * prefix rule (`ref_*`).
 */
@Serializable
data class CustomTrackingParam(
    /** Host the rule applies to; null (or blank) means every host. */
    val host: String? = null,
    val name: String,
)

/**
 * Per-site overrides for the built-in browser (4.3), keyed by host. Every
 * field is nullable: null means "follow the global setting / browser
 * default", so a site entry only pins what the user actually changed.
 */
@Serializable
data class SiteSetting(
    val javaScriptEnabled: Boolean? = null,
    val desktopMode: Boolean? = null,
    /** Site-specific text zoom in percent; null follows the global value. */
    val textZoom: Int? = null,
    /** Force dark rendering regardless of the global page theme. */
    val forceDark: Boolean? = null,
    /** Override the global content-blocker switch for this host. */
    val contentBlocking: Boolean? = null,
) {
    val isEmpty: Boolean
        get() =
            javaScriptEnabled == null &&
                desktopMode == null &&
                textZoom == null &&
                forceDark == null &&
                contentBlocking == null
}

/** When stale tabs are closed automatically on session restore (4.5). */
enum class TabAutoClose(val id: String) {
    NEVER("never"),
    ONE_DAY("1d"),
    ONE_WEEK("1w"),
    ONE_MONTH("1m"),
    ;

    companion object {
        fun fromId(id: String?): TabAutoClose = entries.find { it.id == id } ?: NEVER
    }
}

@Serializable
data class AppSettings(
    val routingEnabled: Boolean = true,
    val selectedBrowserPackage: String? = BrowserLauncher.IN_APP_BROWSER_PACKAGE,
    /** Route ids the user has turned off; a route not in this set is enabled by default. */
    val disabledRoutes: Set<String> = emptySet(),
    /**
     * Route ids set to "ask every time" (2.4): routing hands the link to the
     * ask dialog instead of redirecting immediately.
     */
    val askModeRoutes: Set<String> = emptySet(),
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
    /** Retry a failed routed page load on the next healthy instance (2.2). On by default. */
    val autoFailoverEnabled: Boolean = true,
    /** Measure instance latencies once a day in the background (2.3). Opt-in, off by default. */
    val backgroundLatencyMeasureEnabled: Boolean = false,
    /**
     * Unwrap redirect wrappers and AMP cache URLs locally before routing
     * (3.1). No network is involved; on by default.
     */
    val unwrapRedirectWrappersEnabled: Boolean = true,
    /**
     * Resolve shortener links (t.co, bit.ly, …) with network requests before
     * routing (3.3). Strictly opt-in: it breaks "routing never touches the
     * network" and requests go to the shortener, so off by default.
     */
    val shortLinkResolveEnabled: Boolean = false,
    /** User-defined tracking parameter rules (3.2). */
    val customTrackingParams: List<CustomTrackingParam> = emptyList(),
    /** Block known tracker/content hosts inside the WebView (4.2). On by default. */
    val contentBlockerEnabled: Boolean = true,
    /** Close incognito tabs when the app leaves the foreground (4.5). */
    val closeIncognitoOnExit: Boolean = false,
    /** Automatically drop tabs older than the chosen age on session restore (4.5). */
    val autoCloseOldTabs: String = TabAutoClose.NEVER.id,
    /** Max history entries (4.5); 0 disables history entirely. */
    val historyLimit: Int = 200,
    /** Hide the browser toolbars while the page is scrolled down (4.5). */
    val autoHideToolbar: Boolean = false,
    /** Keep audio playing in a foreground service when the app is backgrounded (4.6). Opt-in. */
    val backgroundAudioEnabled: Boolean = false,
    /** Host -> per-site browser overrides (4.3). */
    val siteSettings: Map<String, SiteSetting> = emptyMap(),
    /** First-run onboarding (5.2) has been finished (or skipped). */
    val onboardingCompleted: Boolean = false,
    /** Epoch millis until which routing is paused (2.6); 0 means not paused. */
    val pausedUntilEpochMillis: Long = 0L,
) {
    fun isRouteEnabled(routeId: String): Boolean = routeId !in disabledRoutes

    fun isRouteAskMode(routeId: String): Boolean = routeId in askModeRoutes

    fun isRoutingPaused(nowMillis: Long = System.currentTimeMillis()): Boolean = pausedUntilEpochMillis > nowMillis

    /** Effective site overrides for [host]; null when the user set nothing for it. */
    fun siteSettingFor(host: String): SiteSetting? {
        val lower = host.lowercase()
        siteSettings[lower]?.let { return it }
        return siteSettings.entries
            .firstOrNull { (domain, _) -> lower == domain || lower.endsWith(".$domain") }
            ?.value
    }

    /**
     * The routing context this settings object produces. Callers attach
     * runtime-only inputs (health data, referrer app, wall clock) via copy.
     */
    fun routingContext(): RoutingContext =
        RoutingContext(
            exceptions = exceptions,
            disabledRoutes = disabledRoutes,
            askModeRoutes = askModeRoutes,
            selectedFrontends = selectedFrontends,
            instanceSelections = instanceSelections,
            customTrackingRules = customTrackingRules(),
        )

    /** User rules split into global and host-scoped sets for the routing core. */
    fun customTrackingRules(): CustomTrackingRules {
        val global = mutableSetOf<String>()
        val byHost = mutableMapOf<String, MutableSet<String>>()
        for (rule in customTrackingParams) {
            val name = rule.name.trim().lowercase()
            if (name.isEmpty()) continue
            val host = rule.host?.trim()?.lowercase()?.trimEnd('.')
            if (host.isNullOrBlank()) {
                global += name
            } else {
                byHost.getOrPut(host) { mutableSetOf() } += name
            }
        }
        return CustomTrackingRules(global = global, byHost = byHost)
    }
}
