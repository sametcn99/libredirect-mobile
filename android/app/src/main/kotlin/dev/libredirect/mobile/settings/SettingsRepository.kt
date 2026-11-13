package dev.libredirect.mobile.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A newer release remembered from the last update check, so the banner survives restarts. */
data class CachedUpdate(
    val versionName: String,
    val releaseUrl: String,
)

/**
 * A corrupted preferences file (partial write, disk error) otherwise throws
 * from the Flow itself on every read with nothing downstream to catch it —
 * resetting to empty preferences trades those saved settings for staying up.
 */
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * All structured values (maps, lists) are stored as JSON strings under a
 * single Preferences key each — Preferences DataStore has no native nested
 * types, and this project already depends on kotlinx.serialization for the
 * manifest, so reusing it here avoids introducing Proto DataStore's schema
 * step for what is still a handful of small settings.
 */
@Suppress("TooManyFunctions")
class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map(::toAppSettings)

    suspend fun current(): AppSettings = settings.first()

    suspend fun setRoutingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ROUTING_ENABLED] = enabled }
    }

    suspend fun setSelectedBrowser(packageName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (packageName == null) {
                prefs.remove(Keys.SELECTED_BROWSER)
            } else {
                prefs[Keys.SELECTED_BROWSER] = packageName
            }
        }
    }

    suspend fun setSearchEngine(engineId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SEARCH_ENGINE] = engineId
        }
    }

    suspend fun setCustomSearchEngine(
        name: String,
        url: String,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_SEARCH_ENGINE_NAME] = name
            prefs[Keys.CUSTOM_SEARCH_ENGINE_URL] = url
        }
    }

    suspend fun setBrowserPageTheme(themeId: String) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.BROWSER_PAGE_THEME] = themeId }
    }

    suspend fun setBrowserAutoDarken(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.BROWSER_AUTO_DARKEN] = enabled }
    }

    suspend fun setClipboardOfferEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.CLIPBOARD_OFFER_ENABLED] = enabled }
    }

    suspend fun setAutoInstanceRefreshEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.AUTO_INSTANCE_REFRESH_ENABLED] = enabled }
    }

    suspend fun setBrowserTextZoom(percent: Int) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.BROWSER_TEXT_ZOOM] = percent }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.APP_LOCK_ENABLED] = enabled }
    }

    // --- 2.x routing-rule settings ---

    /**
     * Sets a service's three-state mode (2.4): OFF adds to disabled routes and
     * clears the ask list, ASK does the reverse, ALWAYS clears both.
     */
    suspend fun setRouteMode(
        routeId: String,
        mode: RouteMode,
    ) {
        context.settingsDataStore.edit { prefs ->
            val disabled = decodeSet(prefs[Keys.DISABLED_ROUTES]).toMutableSet()
            val ask = decodeSet(prefs[Keys.ASK_MODE_ROUTES]).toMutableSet()
            when (mode) {
                RouteMode.ALWAYS -> {
                    disabled -= routeId
                    ask -= routeId
                }
                RouteMode.ASK -> {
                    disabled -= routeId
                    ask += routeId
                }
                RouteMode.OFF -> {
                    disabled += routeId
                    ask -= routeId
                }
            }
            prefs[Keys.DISABLED_ROUTES] = json.encodeToString(disabled)
            prefs[Keys.ASK_MODE_ROUTES] = json.encodeToString(ask)
        }
    }

    /** Pauses routing until [untilEpochMillis] (2.6); 0 or a past stamp resumes. */
    suspend fun setPausedUntil(untilEpochMillis: Long) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.PAUSED_UNTIL] = untilEpochMillis }
    }

    suspend fun setAutoFailoverEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.AUTO_FAILOVER_ENABLED] = enabled }
    }

    suspend fun setBackgroundLatencyMeasureEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.BACKGROUND_LATENCY_ENABLED] = enabled }
    }

    // --- 3.x privacy settings ---

    suspend fun setUnwrapRedirectWrappersEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.UNWRAP_WRAPPERS_ENABLED] = enabled }
    }

    suspend fun setShortLinkResolveEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.SHORT_LINK_RESOLVE_ENABLED] = enabled }
    }

    suspend fun setCustomTrackingParams(params: List<CustomTrackingParam>) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.CUSTOM_TRACKING_PARAMS] = json.encodeToString(params) }
    }

    // --- 4.x browser settings ---

    suspend fun setContentBlockerEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.CONTENT_BLOCKER_ENABLED] = enabled }
    }

    suspend fun setCloseIncognitoOnExit(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.CLOSE_INCOGNITO_ON_EXIT] = enabled }
    }

    suspend fun setAutoCloseOldTabs(id: String) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.AUTO_CLOSE_OLD_TABS] = id }
    }

    suspend fun setHistoryLimit(limit: Int) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.HISTORY_LIMIT] = limit }
    }

    suspend fun setAutoHideToolbar(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.AUTO_HIDE_TOOLBAR] = enabled }
    }

    suspend fun setBackgroundAudioEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.BACKGROUND_AUDIO_ENABLED] = enabled }
    }

    suspend fun setAskBeforeDownload(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.ASK_BEFORE_DOWNLOAD] = enabled }
    }

    suspend fun setDownloadWifiOnly(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    suspend fun setClearIncognitoDownloads(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.CLEAR_INCOGNITO_DOWNLOADS] = enabled }
    }

    suspend fun setSiteSetting(
        host: String,
        setting: SiteSetting?,
    ) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeOrDefault(prefs[Keys.SITE_SETTINGS], emptyMap<String, SiteSetting>())
            val updated =
                if (setting == null || setting.isEmpty) {
                    current - host.lowercase()
                } else {
                    current + (host.lowercase() to setting)
                }
            if (updated.isEmpty()) {
                prefs.remove(Keys.SITE_SETTINGS)
            } else {
                prefs[Keys.SITE_SETTINGS] = json.encodeToString(updated)
            }
        }
    }

    // --- 5.x app settings ---

    suspend fun setRouteEnabled(
        routeId: String,
        enabled: Boolean,
    ) {
        setRouteMode(routeId, if (enabled) RouteMode.ALWAYS else RouteMode.OFF)
    }

    suspend fun setSelectedFrontend(
        routeId: String,
        frontendId: String,
    ) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeStringMap(prefs[Keys.SELECTED_FRONTENDS])
            prefs[Keys.SELECTED_FRONTENDS] = json.encodeToString(current + (routeId to frontendId))
        }
    }

    suspend fun setInstanceSelection(
        routeId: String,
        frontendId: String,
        selection: InstanceSelection,
    ) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeInstanceSelections(prefs[Keys.INSTANCE_SELECTIONS])
            val updated = current + ("$routeId/$frontendId" to selection)
            prefs[Keys.INSTANCE_SELECTIONS] = json.encodeToString(updated)
        }
    }

    suspend fun setExceptions(exceptions: List<ExceptionRule>) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.EXCEPTIONS] = json.encodeToString(exceptions) }
    }

    /**
     * Replaces every user preference in one shot (5.1 backup restore). The
     * caller must have validated the payload first; runtime-only keys
     * (update-check bookkeeping, throttles) are left untouched.
     */
    suspend fun importAll(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.ROUTING_ENABLED] = settings.routingEnabled
            prefs[Keys.SELECTED_BROWSER] = settings.selectedBrowserPackage ?: BrowserLauncher.SYSTEM_DEFAULT_PACKAGE
            prefs[Keys.DISABLED_ROUTES] = json.encodeToString(settings.disabledRoutes)
            prefs[Keys.ASK_MODE_ROUTES] = json.encodeToString(settings.askModeRoutes)
            prefs[Keys.SELECTED_FRONTENDS] = json.encodeToString(settings.selectedFrontends)
            prefs[Keys.INSTANCE_SELECTIONS] = json.encodeToString(settings.instanceSelections)
            prefs[Keys.EXCEPTIONS] = json.encodeToString(settings.exceptions)
            prefs[Keys.SEARCH_ENGINE] = settings.searchEngine
            prefs[Keys.CUSTOM_SEARCH_ENGINE_NAME] = settings.customSearchEngineName
            prefs[Keys.CUSTOM_SEARCH_ENGINE_URL] = settings.customSearchEngineUrl
            prefs[Keys.BROWSER_PAGE_THEME] = settings.browserPageTheme
            prefs[Keys.BROWSER_AUTO_DARKEN] = settings.browserAutoDarken
            prefs[Keys.CLIPBOARD_OFFER_ENABLED] = settings.clipboardOfferEnabled
            prefs[Keys.AUTO_INSTANCE_REFRESH_ENABLED] = settings.autoInstanceRefreshEnabled
            prefs[Keys.BROWSER_TEXT_ZOOM] = settings.browserTextZoom
            prefs[Keys.APP_LOCK_ENABLED] = settings.appLockEnabled
            prefs[Keys.AUTO_FAILOVER_ENABLED] = settings.autoFailoverEnabled
            prefs[Keys.BACKGROUND_LATENCY_ENABLED] = settings.backgroundLatencyMeasureEnabled
            prefs[Keys.UNWRAP_WRAPPERS_ENABLED] = settings.unwrapRedirectWrappersEnabled
            prefs[Keys.SHORT_LINK_RESOLVE_ENABLED] = settings.shortLinkResolveEnabled
            prefs[Keys.CUSTOM_TRACKING_PARAMS] = json.encodeToString(settings.customTrackingParams)
            prefs[Keys.CONTENT_BLOCKER_ENABLED] = settings.contentBlockerEnabled
            prefs[Keys.CLOSE_INCOGNITO_ON_EXIT] = settings.closeIncognitoOnExit
            prefs[Keys.AUTO_CLOSE_OLD_TABS] = settings.autoCloseOldTabs
            prefs[Keys.HISTORY_LIMIT] = settings.historyLimit
            prefs[Keys.AUTO_HIDE_TOOLBAR] = settings.autoHideToolbar
            prefs[Keys.BACKGROUND_AUDIO_ENABLED] = settings.backgroundAudioEnabled
            prefs[Keys.ASK_BEFORE_DOWNLOAD] = settings.askBeforeDownload
            prefs[Keys.DOWNLOAD_WIFI_ONLY] = settings.downloadWifiOnly
            prefs[Keys.CLEAR_INCOGNITO_DOWNLOADS] = settings.clearIncognitoDownloads
            prefs[Keys.SITE_SETTINGS] = json.encodeToString(settings.siteSettings)
            prefs[Keys.PAUSED_UNTIL] = 0L
        }
    }

    // --- runtime bookkeeping (not part of backups) ---

    /** Epoch millis of the last successful GitHub Releases update check; 0 when never checked. */
    suspend fun updateLastCheckAt(): Long = context.settingsDataStore.data.first()[Keys.UPDATE_LAST_CHECK_AT] ?: 0L

    suspend fun setUpdateLastCheckAt(epochMillis: Long) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.UPDATE_LAST_CHECK_AT] = epochMillis }
    }

    /**
     * Epoch millis of the last automatic manifest generation attempt; 0 when
     * never attempted. Attempts (not successes) advance it: a silent
     * background refresh must not hammer a dead network on every launch, and
     * the daily worker / manual refresh remain independent paths.
     */
    suspend fun manifestLastGenerationAt(): Long =
        context.settingsDataStore.data.first()[Keys.MANIFEST_LAST_GENERATION_AT] ?: 0L

    suspend fun setManifestLastGenerationAt(epochMillis: Long) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.MANIFEST_LAST_GENERATION_AT] = epochMillis }
    }

    /** Consecutive failed update checks since the last success; drives the throttle back-off. */
    suspend fun updateFailedChecksSinceSuccess(): Int =
        context.settingsDataStore.data.first()[Keys.UPDATE_FAILED_CHECKS] ?: 0

    suspend fun setUpdateFailedChecksSinceSuccess(count: Int) {
        context.settingsDataStore.edit { prefs ->
            if (count <= 0) {
                prefs.remove(Keys.UPDATE_FAILED_CHECKS)
            } else {
                prefs[Keys.UPDATE_FAILED_CHECKS] = count
            }
        }
    }

    /** Last known newer release from GitHub; null when none is cached. */
    @Suppress("ReturnCount")
    suspend fun cachedUpdate(): CachedUpdate? {
        val prefs = context.settingsDataStore.data.first()
        val version = prefs[Keys.UPDATE_CACHED_VERSION] ?: return null
        if (version.isBlank()) return null
        return CachedUpdate(versionName = version, releaseUrl = prefs[Keys.UPDATE_CACHED_RELEASE_URL].orEmpty())
    }

    /** Blank version clears the cache (used when the check finds nothing newer). */
    suspend fun setCachedUpdate(
        version: String,
        releaseUrl: String,
    ) {
        context.settingsDataStore.edit { prefs ->
            if (version.isBlank()) {
                prefs.remove(Keys.UPDATE_CACHED_VERSION)
                prefs.remove(Keys.UPDATE_CACHED_RELEASE_URL)
            } else {
                prefs[Keys.UPDATE_CACHED_VERSION] = version
                prefs[Keys.UPDATE_CACHED_RELEASE_URL] = releaseUrl
            }
        }
    }

    // One line per stored key is the whole point — splitting the mapping
    // would scatter the key list and make drift between write and read
    // harder to spot, so the mechanical complexity stays in one place.
    @Suppress("CyclomaticComplexMethod")
    private fun toAppSettings(prefs: Preferences): AppSettings =
        AppSettings(
            routingEnabled = prefs[Keys.ROUTING_ENABLED] ?: true,
            selectedBrowserPackage = prefs[Keys.SELECTED_BROWSER] ?: BrowserLauncher.IN_APP_BROWSER_PACKAGE,
            disabledRoutes = decodeSet(prefs[Keys.DISABLED_ROUTES]),
            askModeRoutes = decodeSet(prefs[Keys.ASK_MODE_ROUTES]),
            selectedFrontends = decodeStringMap(prefs[Keys.SELECTED_FRONTENDS]),
            instanceSelections = decodeInstanceSelections(prefs[Keys.INSTANCE_SELECTIONS]),
            exceptions = decodeList(prefs[Keys.EXCEPTIONS]),
            searchEngine = prefs[Keys.SEARCH_ENGINE] ?: "duckduckgo",
            customSearchEngineName = prefs[Keys.CUSTOM_SEARCH_ENGINE_NAME] ?: "",
            customSearchEngineUrl = prefs[Keys.CUSTOM_SEARCH_ENGINE_URL] ?: "",
            browserPageTheme = prefs[Keys.BROWSER_PAGE_THEME] ?: "system",
            browserAutoDarken = prefs[Keys.BROWSER_AUTO_DARKEN] ?: true,
            clipboardOfferEnabled = prefs[Keys.CLIPBOARD_OFFER_ENABLED] ?: false,
            autoInstanceRefreshEnabled = prefs[Keys.AUTO_INSTANCE_REFRESH_ENABLED] ?: false,
            browserTextZoom = prefs[Keys.BROWSER_TEXT_ZOOM] ?: 100,
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
            autoFailoverEnabled = prefs[Keys.AUTO_FAILOVER_ENABLED] ?: true,
            backgroundLatencyMeasureEnabled = prefs[Keys.BACKGROUND_LATENCY_ENABLED] ?: false,
            unwrapRedirectWrappersEnabled = prefs[Keys.UNWRAP_WRAPPERS_ENABLED] ?: true,
            shortLinkResolveEnabled = prefs[Keys.SHORT_LINK_RESOLVE_ENABLED] ?: false,
            customTrackingParams = decodeOrDefault(prefs[Keys.CUSTOM_TRACKING_PARAMS], emptyList()),
            contentBlockerEnabled = prefs[Keys.CONTENT_BLOCKER_ENABLED] ?: true,
            closeIncognitoOnExit = prefs[Keys.CLOSE_INCOGNITO_ON_EXIT] ?: false,
            autoCloseOldTabs = prefs[Keys.AUTO_CLOSE_OLD_TABS] ?: TabAutoClose.NEVER.id,
            historyLimit = prefs[Keys.HISTORY_LIMIT] ?: 200,
            autoHideToolbar = prefs[Keys.AUTO_HIDE_TOOLBAR] ?: false,
            backgroundAudioEnabled = prefs[Keys.BACKGROUND_AUDIO_ENABLED] ?: false,
            askBeforeDownload = prefs[Keys.ASK_BEFORE_DOWNLOAD] ?: true,
            downloadWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: false,
            clearIncognitoDownloads = prefs[Keys.CLEAR_INCOGNITO_DOWNLOADS] ?: true,
            siteSettings = decodeOrDefault(prefs[Keys.SITE_SETTINGS], emptyMap()),
            pausedUntilEpochMillis = prefs[Keys.PAUSED_UNTIL] ?: 0L,
        )

    private fun decodeSet(raw: String?): Set<String> = decodeOrDefault(raw, emptySet())

    private fun decodeStringMap(raw: String?): Map<String, String> = decodeOrDefault(raw, emptyMap())

    private fun decodeInstanceSelections(raw: String?): Map<String, InstanceSelection> =
        decodeOrDefault(raw, emptyMap())

    private fun decodeList(raw: String?): List<ExceptionRule> = decodeOrDefault(raw, emptyList())

    private inline fun <reified T> decodeOrDefault(
        raw: String?,
        default: T,
    ): T =
        try {
            raw?.let { json.decodeFromString<T>(it) } ?: default
        } catch (_: SerializationException) {
            default
        } catch (_: IllegalArgumentException) {
            default
        }

    private object Keys {
        val ROUTING_ENABLED = booleanPreferencesKey("routing_enabled")
        val SELECTED_BROWSER = stringPreferencesKey("selected_browser")
        val DISABLED_ROUTES = stringPreferencesKey("disabled_routes_json")
        val ASK_MODE_ROUTES = stringPreferencesKey("ask_mode_routes_json")
        val SELECTED_FRONTENDS = stringPreferencesKey("selected_frontends_json")
        val INSTANCE_SELECTIONS = stringPreferencesKey("instance_selections_json")
        val EXCEPTIONS = stringPreferencesKey("exceptions_json")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val CUSTOM_SEARCH_ENGINE_NAME = stringPreferencesKey("custom_search_engine_name")
        val CUSTOM_SEARCH_ENGINE_URL = stringPreferencesKey("custom_search_engine_url")
        val BROWSER_PAGE_THEME = stringPreferencesKey("browser_page_theme")
        val BROWSER_AUTO_DARKEN = booleanPreferencesKey("browser_auto_darken")
        val CLIPBOARD_OFFER_ENABLED = booleanPreferencesKey("clipboard_offer_enabled")
        val AUTO_INSTANCE_REFRESH_ENABLED = booleanPreferencesKey("auto_instance_refresh_enabled")
        val BROWSER_TEXT_ZOOM = intPreferencesKey("browser_text_zoom")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
        val AUTO_FAILOVER_ENABLED = booleanPreferencesKey("auto_failover_enabled")
        val BACKGROUND_LATENCY_ENABLED = booleanPreferencesKey("background_latency_measure_enabled")
        val UNWRAP_WRAPPERS_ENABLED = booleanPreferencesKey("unwrap_wrappers_enabled")
        val SHORT_LINK_RESOLVE_ENABLED = booleanPreferencesKey("short_link_resolve_enabled")
        val CUSTOM_TRACKING_PARAMS = stringPreferencesKey("custom_tracking_params_json")
        val CONTENT_BLOCKER_ENABLED = booleanPreferencesKey("content_blocker_enabled")
        val CLOSE_INCOGNITO_ON_EXIT = booleanPreferencesKey("close_incognito_on_exit")
        val AUTO_CLOSE_OLD_TABS = stringPreferencesKey("auto_close_old_tabs")
        val HISTORY_LIMIT = intPreferencesKey("history_limit")
        val AUTO_HIDE_TOOLBAR = booleanPreferencesKey("auto_hide_toolbar")
        val BACKGROUND_AUDIO_ENABLED = booleanPreferencesKey("background_audio_enabled")
        val ASK_BEFORE_DOWNLOAD = booleanPreferencesKey("ask_before_download")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val CLEAR_INCOGNITO_DOWNLOADS = booleanPreferencesKey("clear_incognito_downloads")
        val SITE_SETTINGS = stringPreferencesKey("site_settings_json")
        val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        val MANIFEST_LAST_GENERATION_AT = longPreferencesKey("manifest_last_generation_at")
        val UPDATE_FAILED_CHECKS = intPreferencesKey("update_failed_checks")
        val UPDATE_CACHED_VERSION = stringPreferencesKey("update_cached_version")
        val UPDATE_CACHED_RELEASE_URL = stringPreferencesKey("update_cached_release_url")
    }
}

/** The three-state per-service routing mode (2.4). */
enum class RouteMode {
    ALWAYS,
    ASK,
    OFF,
}
