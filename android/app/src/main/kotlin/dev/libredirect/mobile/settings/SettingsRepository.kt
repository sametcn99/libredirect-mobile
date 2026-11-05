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

    suspend fun setRouteEnabled(
        routeId: String,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeSet(prefs[Keys.DISABLED_ROUTES])
            val updated = if (enabled) current - routeId else current + routeId
            prefs[Keys.DISABLED_ROUTES] = json.encodeToString(updated)
        }
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

    private fun toAppSettings(prefs: Preferences): AppSettings =
        AppSettings(
            routingEnabled = prefs[Keys.ROUTING_ENABLED] ?: true,
            selectedBrowserPackage = prefs[Keys.SELECTED_BROWSER] ?: BrowserLauncher.IN_APP_BROWSER_PACKAGE,
            disabledRoutes = decodeSet(prefs[Keys.DISABLED_ROUTES]),
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
        val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        val MANIFEST_LAST_GENERATION_AT = longPreferencesKey("manifest_last_generation_at")
        val UPDATE_FAILED_CHECKS = intPreferencesKey("update_failed_checks")
        val UPDATE_CACHED_VERSION = stringPreferencesKey("update_cached_version")
        val UPDATE_CACHED_RELEASE_URL = stringPreferencesKey("update_cached_release_url")
    }
}
