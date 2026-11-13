@file:Suppress("TooManyFunctions")

package dev.libredirect.mobile.downloads

import android.webkit.MimeTypeMap
import dev.libredirect.mobile.core.routing.ExceptionMatcher
import dev.libredirect.mobile.core.url.CustomTrackingScope
import dev.libredirect.mobile.core.url.TrackingParams
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.core.url.UrlUnwrapper
import dev.libredirect.mobile.settings.AppSettings
import java.io.File
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

private const val KB_BYTES = 1024.0
private const val MB_BYTES = KB_BYTES * KB_BYTES
private const val GB_BYTES = MB_BYTES * KB_BYTES
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val MAX_FILE_NAME_LENGTH = 140
private const val DEFAULT_BASE_NAME = "download"

private val IMAGE_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "avif", "bmp", "heic", "heif", "ico", "tiff")
private val VIDEO_EXTENSIONS =
    setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "3gp", "ts", "ogv")
private val AUDIO_EXTENSIONS =
    setOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac", "weba", "wma")
private val DOCUMENT_EXTENSIONS =
    setOf("pdf", "txt", "md", "csv", "json", "xml", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "epub")
private val ARCHIVE_OR_APP_EXTENSIONS =
    setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "apk", "xapk", "apks", "aab", "iso")
private val CATEGORY_BY_EXTENSION: Map<String, DownloadCategory> =
    buildMap {
        IMAGE_EXTENSIONS.forEach { put(it, DownloadCategory.IMAGES) }
        VIDEO_EXTENSIONS.forEach { put(it, DownloadCategory.VIDEOS) }
        AUDIO_EXTENSIONS.forEach { put(it, DownloadCategory.AUDIO) }
        DOCUMENT_EXTENSIONS.forEach { put(it, DownloadCategory.DOCUMENTS) }
        ARCHIVE_OR_APP_EXTENSIONS.forEach { put(it, DownloadCategory.ARCHIVES_AND_APPS) }
    }
private val DANGEROUS_EXTENSIONS =
    setOf("apk", "xapk", "apks", "exe", "msi", "bat", "cmd", "sh", "ps1", "vbs", "scr", "jar", "dmg", "deb", "rpm")
private val DANGEROUS_MIME_TYPES =
    setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-sh",
        "application/x-shellscript",
    )
private val FORBIDDEN_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

data class DecodedDataUri(
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DecodedDataUri && mimeType == other.mimeType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

data class PreparedDownloadUrl(
    val cleanedUrl: String,
    val strippedCount: Int,
    val unwrappedLayers: Int,
    val sourceHost: String,
)

sealed interface EtaParts {
    data class Seconds(val seconds: Long) : EtaParts

    data class Minutes(val minutes: Long, val seconds: Long) : EtaParts

    data class Hours(val hours: Long, val minutes: Long) : EtaParts
}

fun fileExtensionOf(fileName: String): String {
    val trimmed = fileName.trim()
    val dot = trimmed.lastIndexOf('.')
    if (dot <= 0 || dot == trimmed.lastIndex) return ""
    return trimmed.substring(dot + 1).lowercase(Locale.US)
}

fun categorizeDownload(
    fileName: String,
    mimeType: String?,
): DownloadCategory? {
    val mime = mimeType?.lowercase(Locale.US).orEmpty()
    val byMime =
        when {
            mime.startsWith("image/") -> DownloadCategory.IMAGES
            mime.startsWith("video/") -> DownloadCategory.VIDEOS
            mime.startsWith("audio/") -> DownloadCategory.AUDIO
            mime == "application/pdf" || mime.startsWith("text/") -> DownloadCategory.DOCUMENTS
            mime == "application/vnd.android.package-archive" ||
                mime.contains("zip") ||
                mime.contains("compressed") -> DownloadCategory.ARCHIVES_AND_APPS
            else -> null
        }
    return byMime ?: CATEGORY_BY_EXTENSION[fileExtensionOf(fileName)]
}

fun isPotentiallyDangerousFile(
    fileName: String,
    mimeType: String?,
): Boolean {
    val ext = fileExtensionOf(fileName)
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
    return ext in DANGEROUS_EXTENSIONS || mime in DANGEROUS_MIME_TYPES
}

fun sanitizeFileName(
    raw: String,
    fallbackMimeType: String? = null,
): String {
    val leaf = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    var cleaned =
        leaf
            .replace(FORBIDDEN_FILE_CHARS, "_")
            .replace("..", "_")
            .trim('.', ' ')
    if (cleaned.isEmpty()) {
        cleaned = DEFAULT_BASE_NAME
    }
    if (fileExtensionOf(cleaned).isEmpty()) {
        extensionFromMimeType(fallbackMimeType)?.let { ext ->
            cleaned = "$cleaned.$ext"
        }
    }
    if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned
    val ext = fileExtensionOf(cleaned)
    return if (ext.isNotEmpty() && ext.length < MAX_FILE_NAME_LENGTH / 2) {
        val stemBudget = MAX_FILE_NAME_LENGTH - ext.length - 1
        "${cleaned.substring(0, stemBudget).trimEnd('.')}.$ext"
    } else {
        cleaned.take(MAX_FILE_NAME_LENGTH)
    }
}

/**
 * Preserves the original extension if the user renamed the file without
 * keeping an extension; explicit user extensions are left intact, while
 * generic `.bin` placeholders are upgraded when a concrete MIME resolves.
 */
fun ensureFileExtension(
    editedName: String,
    originalSuggested: String,
    mimeType: String?,
): String {
    val sanitized = sanitizeFileName(editedName, mimeType)
    val currentExt = fileExtensionOf(sanitized)
    val resolvedExt = extensionFromMimeType(mimeType)?.takeIf { it.isNotBlank() && it != "bin" }
    val originalExt = fileExtensionOf(originalSuggested)
    val missingExtFallback =
        when {
            originalExt == "bin" && resolvedExt != null -> resolvedExt
            originalExt.isNotEmpty() -> originalExt
            else -> resolvedExt.orEmpty()
        }
    return when {
        currentExt == "bin" && editedName == originalSuggested && resolvedExt != null ->
            sanitizeFileName(replaceFileExtension(sanitized, resolvedExt), mimeType)
        currentExt.isNotEmpty() -> sanitized
        missingExtFallback.isNotEmpty() -> "$sanitized.$missingExtFallback"
        else -> sanitized
    }
}

internal fun replaceFileExtension(
    fileName: String,
    newExtension: String,
): String {
    val cleanExt = newExtension.trim().trimStart('.').lowercase(Locale.US)
    if (cleanExt.isEmpty()) return fileName
    val trimmed = fileName.trim()
    val dot = trimmed.lastIndexOf('.')
    val stem =
        if (dot > 0) {
            trimmed.substring(0, dot).trimEnd('.')
        } else {
            trimmed
        }
    val safeStem = stem.ifEmpty { DEFAULT_BASE_NAME }
    return "$safeStem.$cleanExt"
}

private val GENERIC_MIME_TYPES =
    setOf(
        "application/octet-stream",
        "application/binary",
        "binary/octet-stream",
        "application/x-binary",
    )

private val FALLBACK_EXT_BY_MIME =
    mapOf(
        "application/pdf" to "pdf",
        "application/json" to "json",
        "application/zip" to "zip",
        "application/vnd.android.package-archive" to "apk",
        "text/plain" to "txt",
        "text/html" to "html",
        "text/markdown" to "md",
        "text/csv" to "csv",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/svg+xml" to "svg",
        "image/avif" to "avif",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/quicktime" to "mov",
        "video/x-matroska" to "mkv",
        "video/3gpp" to "3gp",
        "audio/mpeg" to "mp3",
        "audio/ogg" to "ogg",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
    )
private val FALLBACK_MIME_BY_EXT = FALLBACK_EXT_BY_MIME.entries.associate { (mime, ext) -> ext to mime }
private val EXTRA_MIME_BY_EXT =
    mapOf(
        "jpeg" to "image/jpeg",
        "m4v" to "video/mp4",
    )

fun normalizeMimeType(mimeType: String?): String? =
    mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.ifEmpty { null }

fun isGenericMimeType(mimeType: String?): Boolean {
    val clean = normalizeMimeType(mimeType) ?: return true
    return clean in GENERIC_MIME_TYPES
}

fun extensionFromMimeType(mimeType: String?): String? {
    val cleanMime =
        normalizeMimeType(mimeType)
            ?.takeIf { it !in GENERIC_MIME_TYPES }
            ?: return null
    val systemExt =
        try {
            MimeTypeMap.getSingleton()?.getExtensionFromMimeType(cleanMime)
        } catch (_: RuntimeException) {
            null
        }
    return systemExt ?: FALLBACK_EXT_BY_MIME[cleanMime]
}

fun guessMimeType(
    fileName: String,
    declaredMimeType: String?,
): String {
    val cleanDeclared = normalizeMimeType(declaredMimeType)
    if (cleanDeclared != null && cleanDeclared !in GENERIC_MIME_TYPES) {
        return cleanDeclared
    }
    val ext = fileExtensionOf(fileName)
    val mapped =
        if (ext.isNotEmpty()) {
            try {
                MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
            } catch (_: RuntimeException) {
                null
            } ?: FALLBACK_MIME_BY_EXT[ext] ?: EXTRA_MIME_BY_EXT[ext]
        } else {
            null
        }
    return mapped ?: cleanDeclared ?: "application/octet-stream"
}

fun formatByteSize(
    bytes: Long,
    locale: Locale = Locale.getDefault(),
): String {
    if (bytes < 0L) return ""
    val value = bytes.toDouble()
    return when {
        value < KB_BYTES -> "$bytes B"
        value < MB_BYTES -> String.format(locale, "%.1f KB", value / KB_BYTES)
        value < GB_BYTES -> String.format(locale, "%.1f MB", value / MB_BYTES)
        else -> String.format(locale, "%.2f GB", value / GB_BYTES)
    }
}

fun estimateEtaSeconds(
    bytesDownloaded: Long,
    totalBytes: Long,
    speedBytesPerSec: Long,
): Long? {
    if (totalBytes <= 0L || speedBytesPerSec <= 0L || bytesDownloaded >= totalBytes) return null
    val remaining = totalBytes - bytesDownloaded
    return (remaining / speedBytesPerSec).coerceAtLeast(1L)
}

fun etaParts(seconds: Long): EtaParts =
    when {
        seconds < SECONDS_PER_MINUTE -> EtaParts.Seconds(seconds.coerceAtLeast(1L))
        seconds < SECONDS_PER_HOUR -> {
            val mins = seconds / SECONDS_PER_MINUTE
            val secs = seconds % SECONDS_PER_MINUTE
            EtaParts.Minutes(mins, secs)
        }
        else -> {
            val hours = seconds / SECONDS_PER_HOUR
            val mins = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
            EtaParts.Hours(hours, mins)
        }
    }

fun filterDownloads(
    items: List<DownloadItem>,
    query: String,
    category: DownloadCategory,
): List<DownloadItem> {
    val trimmed = query.trim()
    return items.filter { item ->
        val matchesCategory =
            when (category) {
                DownloadCategory.ALL -> true
                DownloadCategory.ACTIVE -> item.status.isInProgressOrPaused
                DownloadCategory.COMPLETED -> item.status == DownloadStatus.COMPLETED
                DownloadCategory.FAILED ->
                    item.status == DownloadStatus.FAILED || item.status == DownloadStatus.CANCELED
                DownloadCategory.IMAGES,
                DownloadCategory.VIDEOS,
                DownloadCategory.AUDIO,
                DownloadCategory.DOCUMENTS,
                DownloadCategory.ARCHIVES_AND_APPS,
                -> categorizeDownload(item.fileName, item.mimeType) == category
            }
        if (!matchesCategory) return@filter false
        if (trimmed.isEmpty()) return@filter true
        item.fileName.contains(trimmed, ignoreCase = true) ||
            item.sourceHost.contains(trimmed, ignoreCase = true) ||
            item.cleanedUrl.contains(trimmed, ignoreCase = true)
    }
}

@Suppress("ReturnCount")
fun parseDataUri(dataUri: String): DecodedDataUri? {
    if (!dataUri.startsWith("data:", ignoreCase = true)) return null
    val comma = dataUri.indexOf(',')
    if (comma < 0) return null
    val header = dataUri.substring("data:".length, comma)
    val payload = dataUri.substring(comma + 1)
    val parts = header.split(';')
    val isBase64 = parts.any { it.equals("base64", ignoreCase = true) }
    val rawMime = parts.firstOrNull()?.trim()?.takeIf { it.contains('/') } ?: "application/octet-stream"
    val bytes =
        try {
            if (isBase64) {
                val compact = payload.replace("\n", "").replace("\r", "").replace(" ", "")
                Base64.getDecoder().decode(compact)
            } else {
                // RFC 2397 keeps literal '+' intact; URLDecoder treats '+' as space.
                URLDecoder.decode(payload.replace("+", "%2B"), Charsets.UTF_8.name())
                    .toByteArray(Charsets.UTF_8)
            }
        } catch (_: IllegalArgumentException) {
            return null
        }
    return DecodedDataUri(mimeType = rawMime, bytes = bytes)
}

fun prepareDownloadUrl(
    rawUrl: String,
    settings: AppSettings,
): PreparedDownloadUrl {
    val isHttp =
        rawUrl.startsWith("http://", ignoreCase = true) ||
            rawUrl.startsWith("https://", ignoreCase = true)
    val originalParsed = if (isHttp) UrlParser.parse(rawUrl) else null
    val isExcepted =
        originalParsed != null && ExceptionMatcher.matches(originalParsed, settings.exceptions)
    return if (!isHttp || isExcepted) {
        PreparedDownloadUrl(
            cleanedUrl = rawUrl,
            strippedCount = 0,
            unwrappedLayers = 0,
            sourceHost = originalParsed?.host.orEmpty(),
        )
    } else {
        cleanHttpDownloadUrl(rawUrl, originalParsed?.host.orEmpty(), settings)
    }
}

private fun cleanHttpDownloadUrl(
    rawUrl: String,
    fallbackHost: String,
    settings: AppSettings,
): PreparedDownloadUrl {
    var working = rawUrl
    var unwrappedLayers = 0
    if (settings.unwrapRedirectWrappersEnabled) {
        val unwrapped = UrlUnwrapper.unwrapAll(working)
        if (unwrapped.url != working) {
            working = unwrapped.url
            unwrappedLayers = unwrapped.count
        }
    }
    val parsed = UrlParser.parse(working)
    if (parsed == null) {
        return PreparedDownloadUrl(
            cleanedUrl = working,
            strippedCount = 0,
            unwrappedLayers = unwrappedLayers,
            sourceHost = fallbackHost,
        )
    }
    val rules = settings.customTrackingRules()
    val scope = CustomTrackingScope(global = rules.global, byHost = rules.byHost)
    val strippedCount = TrackingParams.removedCount(parsed.rawQuery, parsed.host, scope)
    val cleaned = TrackingParams.stripFrom(working, parsed, scope)
    return PreparedDownloadUrl(
        cleanedUrl = cleaned,
        strippedCount = strippedCount,
        unwrappedLayers = unwrappedLayers,
        sourceHost = parsed.host,
    )
}

fun uniqueFileFor(
    directory: File,
    fileName: String,
): File {
    val sanitized = sanitizeFileName(fileName)
    var candidate = File(directory, sanitized)
    if (!candidate.exists()) return candidate
    val ext = fileExtensionOf(sanitized)
    val stem =
        if (ext.isNotEmpty()) {
            sanitized.substring(0, sanitized.length - ext.length - 1)
        } else {
            sanitized
        }
    var counter = 1
    while (candidate.exists()) {
        val nextName = if (ext.isNotEmpty()) "$stem ($counter).$ext" else "$stem ($counter)"
        candidate = File(directory, nextName)
        counter++
    }
    return candidate
}
