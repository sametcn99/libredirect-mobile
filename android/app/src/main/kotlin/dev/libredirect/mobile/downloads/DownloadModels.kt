package dev.libredirect.mobile.downloads

import kotlinx.serialization.Serializable

/** Lifecycle state of an in-app browser download. */
@Serializable
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_FOR_WIFI,
    COMPLETED,
    FAILED,
    CANCELED,
    ;

    val isActive: Boolean
        get() = this == RUNNING || this == QUEUED

    val isInProgressOrPaused: Boolean
        get() = this == RUNNING || this == QUEUED || this == PAUSED || this == WAITING_FOR_WIFI
}

/** Category filter tabs shown in the Downloads sheet. */
enum class DownloadCategory {
    ALL,
    ACTIVE,
    COMPLETED,
    IMAGES,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
    ARCHIVES_AND_APPS,
    FAILED,
}

/** Categorized failure reasons surfaced to the UI with clear explanations. */
@Serializable
enum class DownloadFailureReason {
    NONE,
    NETWORK_ERROR,
    HTTP_ERROR,
    STORAGE_ERROR,
    INVALID_URL,
    JS_DISABLED,
    BLOB_ERROR,
    USER_CANCELED,
    UNKNOWN,
}

/** Source scheme class of a download entry. */
@Serializable
enum class DownloadSourceKind {
    HTTP,
    DATA_URI,
    BLOB_URI,
}

/**
 * Persistent record and live UI state of a single download managed by
 * [DownloadRepository]. Cookies and sensitive auth headers are never serialized
 * here; they live only in memory while a transfer is active.
 */
@Serializable
data class DownloadItem(
    val id: String,
    val fileName: String,
    val originalUrl: String,
    val cleanedUrl: String,
    val strippedTrackingCount: Int = 0,
    val sourceHost: String = "",
    val sourceKind: DownloadSourceKind = DownloadSourceKind.HTTP,
    val mimeType: String = "application/octet-stream",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val localUri: String? = null,
    val filePath: String? = null,
    val isPrivateStorage: Boolean = false,
    val isIncognito: Boolean = false,
    val wifiOnly: Boolean = false,
    val userAgent: String? = null,
    val referer: String? = null,
    val httpStatusCode: Int? = null,
    val failureReason: DownloadFailureReason = DownloadFailureReason.NONE,
    val failureDetail: String? = null,
    val fileMissing: Boolean = false,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long? = null,
) {
    val progressFraction: Float?
        get() =
            if (totalBytes > 0L) {
                (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
}

/**
 * Staged download metadata presented to the user in the Pre-Download
 * confirmation sheet before bytes are fetched (or streamed for `blob:`/`data:`).
 */
data class PendingDownloadRequest(
    val id: String,
    val originalUrl: String,
    val cleanedUrl: String,
    val strippedTrackingCount: Int,
    val sourceHost: String,
    val sourceKind: DownloadSourceKind,
    val suggestedFileName: String,
    val mimeType: String,
    val contentLength: Long,
    val userAgent: String,
    val referer: String?,
    val cookies: String?,
    val isIncognito: Boolean,
    val isPotentiallyDangerous: Boolean,
    val blobRequestId: String? = null,
    val tabId: String? = null,
)

/** Resolved destination filename and MIME type for a download entry. */
data class ResolvedDownloadMetadata(
    val fileName: String,
    val mimeType: String,
)

internal data class UrlMediaHint(
    val fileName: String?,
    val mimeType: String?,
)

/** One-shot events emitted by [DownloadRepository] for in-app Snackbars. */
sealed interface DownloadEvent {
    data class Started(val item: DownloadItem) : DownloadEvent

    data class Completed(val item: DownloadItem) : DownloadEvent

    data class Failed(val item: DownloadItem) : DownloadEvent
}
