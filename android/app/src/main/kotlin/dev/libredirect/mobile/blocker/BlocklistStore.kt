package dev.libredirect.mobile.blocker

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Persistence for the active blocklist: a validated remote download lands
 * in `files/blocklist/active.txt` (atomically), and the APK's bundled asset
 * is the fallback whenever no remote list has been activated. Reading is
 * lazy and cached; a corrupted or missing file degrades to the bundled list
 * rather than disabling blocking.
 */
@Suppress("TooGenericExceptionCaught") // best-effort IO: any failure degrades to the bundled list
class BlocklistStore(private val context: Context) {
    private val activeFile: File get() = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    @Volatile
    private var cached: Set<String>? = null

    /** The blocklist to use right now: last activated remote list, else the bundled one. */
    fun activeBlocklist(): Set<String> {
        cached?.let { return it }
        val parsed =
            try {
                if (activeFile.exists()) {
                    ContentBlocker.parseHosts(activeFile.readText())
                } else {
                    bundledBlocklist()
                }
            } catch (error: Exception) {
                // Any IO/parse failure must degrade to the bundled list, not break browsing.
                Log.e(TAG, "Blocklist read failed; using bundled list", error)
                bundledBlocklist()
            }
        cached = parsed
        return parsed
    }

    fun bundledBlocklist(): Set<String> =
        try {
            ContentBlocker.parseHosts(
                context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() },
            )
        } catch (error: Exception) {
            Log.e(TAG, "Bundled blocklist unavailable", error)
            emptySet()
        }

    /**
     * Atomically activates a validated raw list. Returns false (keeping any
     * previously active list) when writing fails.
     */
    fun activate(raw: String): Boolean =
        try {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val temp = File(dir, "$FILE_NAME.tmp")
            temp.writeText(raw)
            if (!temp.renameTo(activeFile)) {
                activeFile.delete()
                temp.renameTo(activeFile)
            } else {
                true
            }.also { activated ->
                if (activated) cached = null
            }
        } catch (error: Exception) {
            Log.e(TAG, "Blocklist activation failed", error)
            false
        }

    fun invalidateCache() {
        cached = null
    }

    companion object {
        private const val TAG = "BlocklistStore"
        private const val DIR_NAME = "blocklist"
        private const val FILE_NAME = "active.txt"
        private const val BUNDLED_ASSET = "blocklist.txt"
    }
}
