package dev.libredirect.mobile.settings

import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.core.url.UrlValidation
import dev.libredirect.mobile.ui.browser.BrowserBookmark
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single-file backup of user preferences (5.1): every [AppSettings] field,
 * custom services, and optionally bookmarks. SAF import/export only — no
 * storage permission, and the file stays under the user's control.
 *
 * [AppSettings] is serialized directly so a newly added settings field is
 * automatically covered by the codec (a dedicated payload type would rot);
 * [BACKUP_SCHEMA_VERSION] guards future shape changes. Import validates
 * before anything is applied (see [decode]).
 */
object SettingsBackupCodec {
    const val BACKUP_SCHEMA_VERSION = 1
    const val MAX_BACKUP_BYTES = 2 * 1024 * 1024

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Serializable
    data class Backup(
        val backupSchemaVersion: Int = BACKUP_SCHEMA_VERSION,
        val exportedAtEpochMillis: Long,
        val appVersionName: String,
        val settings: AppSettings,
        val customRoutes: List<Route> = emptyList(),
        val bookmarks: List<BrowserBookmark> = emptyList(),
        /** Names of hosts that had site settings, for the import summary. */
        val siteSettingHosts: List<String> = emptyList(),
    )

    sealed interface ImportResult {
        data class Success(val backup: Backup) : ImportResult

        data class Failure(val reason: Reason) : ImportResult
    }

    enum class Reason {
        MALFORMED,
        UNSUPPORTED_VERSION,
        TOO_LARGE,
        INVALID_CUSTOM_INSTANCE,
    }

    fun encode(backup: Backup): String = json.encodeToString(backup)

    /**
     * Parses and validates a backup file's content. Every pinned/custom
     * instance URL inside instance selections is re-checked against the same
     * HTTPS origin rule the settings UI enforces, so a hand-edited backup
     * cannot smuggle in a non-HTTPS or private target.
     */
    @Suppress("ReturnCount")
    fun decode(content: String): ImportResult {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
            return ImportResult.Failure(Reason.TOO_LARGE)
        }
        val backup =
            try {
                json.decodeFromString<Backup>(content)
            } catch (_: SerializationException) {
                return ImportResult.Failure(Reason.MALFORMED)
            } catch (_: IllegalArgumentException) {
                return ImportResult.Failure(Reason.MALFORMED)
            }
        if (backup.backupSchemaVersion != BACKUP_SCHEMA_VERSION) {
            return ImportResult.Failure(Reason.UNSUPPORTED_VERSION)
        }
        val instanceUrls =
            backup.settings.instanceSelections.values.mapNotNull { selection ->
                when (selection) {
                    is InstanceSelection.Pinned -> selection.instance
                    is InstanceSelection.Custom -> selection.instance
                    InstanceSelection.Automatic, InstanceSelection.Fastest -> null
                }
            }
        if (instanceUrls.any { instance -> !UrlValidation.isValidHttpsOrigin(instance) }) {
            return ImportResult.Failure(Reason.INVALID_CUSTOM_INSTANCE)
        }
        return ImportResult.Success(backup)
    }
}
