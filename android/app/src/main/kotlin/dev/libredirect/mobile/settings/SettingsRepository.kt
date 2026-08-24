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
import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map(::toAppSettings)

    suspend fun setRoutingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ROUTING_ENABLED] = enabled }
    }

    suspend fun setSelectedBrowser(packageName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (packageName == null) prefs.remove(Keys.SELECTED_BROWSER) else prefs[Keys.SELECTED_BROWSER] = packageName
        }
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

    suspend fun setManifestMetadata(
        revision: Int,
        updatedAtEpochMillis: Long,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MANIFEST_REVISION] = revision
            prefs[Keys.MANIFEST_UPDATED_AT] = updatedAtEpochMillis
        }
    }

    private fun toAppSettings(prefs: Preferences): AppSettings =
        AppSettings(
            routingEnabled = prefs[Keys.ROUTING_ENABLED] ?: true,
            selectedBrowserPackage = prefs[Keys.SELECTED_BROWSER],
            disabledRoutes = decodeSet(prefs[Keys.DISABLED_ROUTES]),
            selectedFrontends = decodeStringMap(prefs[Keys.SELECTED_FRONTENDS]),
            instanceSelections = decodeInstanceSelections(prefs[Keys.INSTANCE_SELECTIONS]),
            exceptions = decodeList(prefs[Keys.EXCEPTIONS]),
            manifestRevision = prefs[Keys.MANIFEST_REVISION],
            manifestUpdatedAtEpochMillis = prefs[Keys.MANIFEST_UPDATED_AT],
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
        val MANIFEST_REVISION = intPreferencesKey("manifest_revision")
        val MANIFEST_UPDATED_AT = longPreferencesKey("manifest_updated_at")
    }
}
