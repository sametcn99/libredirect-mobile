package dev.libredirect.mobile.ui.browser

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.browserDataStore by preferencesDataStore(
    name = "browser_data",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Mutable history cap; the effective limit comes from settings (4.5). */
const val DEFAULT_HISTORY_LIMIT = 200

/** Case-insensitive title/url filter behind the history search field (B5). */
fun filterBrowserHistory(
    history: List<BrowserHistoryItem>,
    query: String,
): List<BrowserHistoryItem> =
    if (query.isBlank()) {
        history
    } else {
        history.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.url.contains(query, ignoreCase = true)
        }
    }

/** Latency entries relevant to the picker's instances, seeding it from a previous session (A3). */
fun filterLatencies(
    cached: Map<String, Long>,
    instances: List<String>,
): Map<String, Long> = if (cached.isEmpty()) emptyMap() else cached.filterKeys { it in instances.toSet() }

/**
 * Local privacy statistics (3.4): what routing actually did on this device.
 * Purely cumulative counters, resettable, never leaves the device.
 */
@Serializable
data class PrivacyStats(
    val totalRedirects: Long = 0,
    val strippedTrackingParams: Long = 0,
    val unwrappedWrappers: Long = 0,
    val resolvedShortLinks: Long = 0,
    val blockedRequests: Long = 0,
) {
    operator fun plus(other: PrivacyStats): PrivacyStats =
        PrivacyStats(
            totalRedirects = totalRedirects + other.totalRedirects,
            strippedTrackingParams = strippedTrackingParams + other.strippedTrackingParams,
            unwrappedWrappers = unwrappedWrappers + other.unwrappedWrappers,
            resolvedShortLinks = resolvedShortLinks + other.resolvedShortLinks,
            blockedRequests = blockedRequests + other.blockedRequests,
        )
}

@Suppress("TooManyFunctions")
class BrowserDataRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val bookmarks: Flow<List<BrowserBookmark>> =
        context.browserDataStore.data.map { prefs ->
            decodeList(prefs[Keys.BOOKMARKS])
        }

    val history: Flow<List<BrowserHistoryItem>> =
        context.browserDataStore.data.map { prefs ->
            decodeList(prefs[Keys.HISTORY])
        }

    /** Local redirect counters, routeId -> total redirects. Never leaves the device. */
    val redirectStats: Flow<Map<String, Int>> =
        context.browserDataStore.data.map { prefs ->
            decodeOrDefault(prefs[Keys.REDIRECT_STATS], emptyMap())
        }

    /** Last measured instance latencies (instance origin -> TTFB ms, [LATENCY_FAILED_MILLIS] = unreachable). */
    suspend fun cachedLatencies(): Map<String, Long> =
        context.browserDataStore.data.first()[Keys.INSTANCE_LATENCIES]?.let { raw ->
            decodeOrDefault(raw, emptyMap<String, Long>())
        } ?: emptyMap()

    suspend fun saveLatencies(latencies: Map<String, Long>) {
        if (latencies.isEmpty()) return
        context.browserDataStore.edit { prefs ->
            val current = decodeOrDefault(prefs[Keys.INSTANCE_LATENCIES], emptyMap<String, Long>())
            prefs[Keys.INSTANCE_LATENCIES] = json.encodeToString(current + latencies)
        }
    }

    suspend fun lastLatencyMeasureAt(): Long =
        context.browserDataStore.data.first()[Keys.LAST_LATENCY_MEASURE_AT] ?: 0L

    suspend fun lastLatencyMeasureCount(): Int =
        context.browserDataStore.data.first()[Keys.LAST_LATENCY_MEASURE_COUNT] ?: 0

    suspend fun setLastLatencyMeasureResult(atMillis: Long, count: Int) {
        context.browserDataStore.edit { prefs ->
            prefs[Keys.LAST_LATENCY_MEASURE_AT] = atMillis
            prefs[Keys.LAST_LATENCY_MEASURE_COUNT] = count
        }
    }

    suspend fun clearLatencies() {
        context.browserDataStore.edit { prefs ->
            prefs.remove(Keys.INSTANCE_LATENCIES)
            prefs.remove(Keys.LAST_LATENCY_MEASURE_AT)
            prefs.remove(Keys.LAST_LATENCY_MEASURE_COUNT)
        }
    }

    /**
     * Epoch millis of each instance's last observed load failure (2.1/2.2);
     * [InstanceHealth]'s cooldown logic reads this through RoutingContext.
     */
    suspend fun instanceFailures(): Map<String, Long> =
        context.browserDataStore.data.first()[Keys.INSTANCE_FAILURES]?.let { raw ->
            decodeOrDefault(raw, emptyMap<String, Long>())
        } ?: emptyMap()

    suspend fun recordInstanceFailure(
        instance: String,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        if (instance.isBlank()) return
        context.browserDataStore.edit { prefs ->
            val current = decodeOrDefault(prefs[Keys.INSTANCE_FAILURES], emptyMap<String, Long>())
            prefs[Keys.INSTANCE_FAILURES] = json.encodeToString(current + (instance to atMillis))
        }
    }

    /** Local privacy counters (3.4); the card on Home shows and resets these. */
    val privacyStats: Flow<PrivacyStats> =
        context.browserDataStore.data.map { prefs ->
            decodeOrDefault(prefs[Keys.PRIVACY_STATS], PrivacyStats())
        }

    suspend fun addPrivacyStats(delta: PrivacyStats) {
        if (delta == PrivacyStats()) return
        context.browserDataStore.edit { prefs ->
            val current = decodeOrDefault(prefs[Keys.PRIVACY_STATS], PrivacyStats())
            prefs[Keys.PRIVACY_STATS] = json.encodeToString(current + delta)
        }
    }

    suspend fun resetPrivacyStats() {
        context.browserDataStore.edit { prefs ->
            prefs[Keys.PRIVACY_STATS] = json.encodeToString(PrivacyStats())
        }
    }

    suspend fun recordRedirect(routeId: String) {
        if (routeId.isBlank()) return
        context.browserDataStore.edit { prefs ->
            val current = decodeOrDefault(prefs[Keys.REDIRECT_STATS], emptyMap<String, Int>())
            prefs[Keys.REDIRECT_STATS] = json.encodeToString(current + (routeId to (current[routeId] ?: 0) + 1))
        }
    }

    suspend fun addBookmark(
        title: String,
        url: String,
    ) {
        if (url.isBlank()) return
        context.browserDataStore.edit { prefs ->
            val current = decodeList<BrowserBookmark>(prefs[Keys.BOOKMARKS])
            val filtered = current.filterNot { it.url == url }
            val newBookmark =
                BrowserBookmark(
                    title = title.ifBlank { url },
                    url = url,
                )
            prefs[Keys.BOOKMARKS] = json.encodeToString(listOf(newBookmark) + filtered)
        }
    }

    suspend fun importBookmarks(imported: List<BrowserBookmark>): Int {
        if (imported.isEmpty()) return 0
        var addedCount = 0
        context.browserDataStore.edit { prefs ->
            val current = decodeList<BrowserBookmark>(prefs[Keys.BOOKMARKS])
            val existingUrls = current.map { it.url }.toSet()
            val newItems =
                imported
                    .filter { it.url.isNotBlank() && it.url !in existingUrls }
                    .distinctBy { it.url }
            addedCount = newItems.size
            if (newItems.isNotEmpty()) {
                prefs[Keys.BOOKMARKS] = json.encodeToString(newItems + current)
            }
        }
        return addedCount
    }

    suspend fun removeBookmark(id: String) {
        context.browserDataStore.edit { prefs ->
            val current = decodeList<BrowserBookmark>(prefs[Keys.BOOKMARKS])
            prefs[Keys.BOOKMARKS] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    suspend fun removeBookmarkByUrl(url: String) {
        context.browserDataStore.edit { prefs ->
            val current = decodeList<BrowserBookmark>(prefs[Keys.BOOKMARKS])
            prefs[Keys.BOOKMARKS] = json.encodeToString(current.filterNot { it.url == url })
        }
    }

    suspend fun addHistory(
        title: String,
        url: String,
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ) {
        if (limit <= 0) return // 0 = keep no history at all (4.5)
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return
        context.browserDataStore.edit { prefs ->
            val current = decodeList<BrowserHistoryItem>(prefs[Keys.HISTORY])
            val filtered = current.filterNot { it.url == url }
            val newItem =
                BrowserHistoryItem(
                    title = title.ifBlank { url },
                    url = url,
                )
            val updated = (listOf(newItem) + filtered).take(limit)
            prefs[Keys.HISTORY] = json.encodeToString(updated)
        }
    }

    suspend fun removeHistoryItem(id: String) {
        context.browserDataStore.edit { prefs ->
            val current = decodeList<BrowserHistoryItem>(prefs[Keys.HISTORY])
            prefs[Keys.HISTORY] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    suspend fun clearHistory() {
        context.browserDataStore.edit { prefs ->
            prefs.remove(Keys.HISTORY)
        }
    }

    /**
     * The open-tab session, persisted so tabs survive process death. Only
     * URLs and redirect metadata are stored — WebView page state is not.
     */
    suspend fun loadSession(): PersistedSession? =
        context.browserDataStore.data.first()[Keys.SESSION]?.let { raw ->
            try {
                json.decodeFromString<PersistedSession>(raw)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    suspend fun saveSession(session: PersistedSession) {
        context.browserDataStore.edit { prefs ->
            prefs[Keys.SESSION] = json.encodeToString(session)
        }
    }

    private inline fun <reified T> decodeList(raw: String?): List<T> =
        if (raw == null) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<T>>(raw)
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }

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
        val BOOKMARKS = stringPreferencesKey("bookmarks_json")
        val HISTORY = stringPreferencesKey("history_json")
        val SESSION = stringPreferencesKey("session_json")
        val REDIRECT_STATS = stringPreferencesKey("redirect_stats_json")
        val INSTANCE_LATENCIES = stringPreferencesKey("instance_latencies_json")
        val INSTANCE_FAILURES = stringPreferencesKey("instance_failures_json")
        val PRIVACY_STATS = stringPreferencesKey("privacy_stats_json")
        val LAST_LATENCY_MEASURE_AT = longPreferencesKey("last_latency_measure_at")
        val LAST_LATENCY_MEASURE_COUNT = intPreferencesKey("last_latency_measure_count")
    }

    companion object {
        /** Marks an instance that failed to answer the latency probe. */
        const val LATENCY_FAILED_MILLIS = -1L
    }
}
