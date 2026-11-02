package dev.libredirect.mobile.ui.browser

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.browserDataStore by preferencesDataStore(
    name = "browser_data",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private const val MAX_HISTORY_ENTRIES = 200

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
    ) {
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return
        context.browserDataStore.edit { prefs ->
            val current = decodeList<BrowserHistoryItem>(prefs[Keys.HISTORY])
            val filtered = current.filterNot { it.url == url }
            val newItem =
                BrowserHistoryItem(
                    title = title.ifBlank { url },
                    url = url,
                )
            val updated = (listOf(newItem) + filtered).take(MAX_HISTORY_ENTRIES)
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

    private object Keys {
        val BOOKMARKS = stringPreferencesKey("bookmarks_json")
        val HISTORY = stringPreferencesKey("history_json")
    }
}
