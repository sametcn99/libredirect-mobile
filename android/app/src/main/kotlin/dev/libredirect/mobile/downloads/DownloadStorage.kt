package dev.libredirect.mobile.downloads

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

private const val COPY_BUFFER_BYTES = 16 * 1024

data class PublishedLocation(
    val localUri: String,
    val filePath: String?,
    val finalFileName: String,
    val isPrivateStorage: Boolean,
)

enum class OpenFileResult {
    OPENED,
    FILE_MISSING,
    NO_APP_FOUND,
}

interface DownloadFileStore {
    fun stagingFile(id: String): File

    fun deleteStagingFile(id: String)

    fun publishCompletedFile(
        stagingFile: File,
        desiredName: String,
        mimeType: String,
    ): PublishedLocation?

    fun isFileAvailable(item: DownloadItem): Boolean

    fun deleteLocalFile(item: DownloadItem): Boolean
}

class DownloadStorage(private val context: Context) : DownloadFileStore {
    private val stagingDir: File by lazy {
        File(context.filesDir, "download_staging").apply { mkdirs() }
    }

    override fun stagingFile(id: String): File {
        stagingDir.mkdirs()
        return File(stagingDir, "$id.part")
    }

    override fun deleteStagingFile(id: String) {
        val file = File(stagingDir, "$id.part")
        if (file.exists()) {
            file.delete()
        }
    }

    override fun publishCompletedFile(
        stagingFile: File,
        desiredName: String,
        mimeType: String,
    ): PublishedLocation? {
        if (!stagingFile.exists()) return null
        val sanitized = sanitizeFileName(desiredName, mimeType)
        val resolvedMime = guessMimeType(sanitized, mimeType)
        val mediaStoreResult =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(stagingFile, sanitized, resolvedMime)
            } else {
                null
            }
        return mediaStoreResult ?: publishToAppDownloads(stagingFile, sanitized)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("ReturnCount")
    private fun publishToMediaStore(
        stagingFile: File,
        fileName: String,
        mimeType: String,
    ): PublishedLocation? {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (_: Exception) {
                null
            } ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                stagingFile.inputStream().use { input ->
                    input.copyTo(out, COPY_BUFFER_BYTES)
                }
                out.flush()
            } ?: throw IOException("Unable to open MediaStore output stream")
            val finalized = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, finalized, null, null)
            val actualName = queryDisplayName(uri) ?: fileName
            stagingFile.delete()
            PublishedLocation(
                localUri = uri.toString(),
                filePath = null,
                finalFileName = actualName,
                isPrivateStorage = false,
            )
        } catch (_: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> firstColumnString(cursor, OpenableColumns.DISPLAY_NAME) }
        } catch (_: Exception) {
            null
        }

    private fun firstColumnString(
        cursor: android.database.Cursor,
        columnName: String,
    ): String? {
        if (!cursor.moveToFirst()) return null
        val idx = cursor.getColumnIndex(columnName)
        return if (idx >= 0) cursor.getString(idx) else null
    }

    private fun publishToAppDownloads(
        stagingFile: File,
        fileName: String,
    ): PublishedLocation? {
        val targetDir =
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
                ?: File(context.filesDir, "downloads").apply { mkdirs() }
        val destination = uniqueFileFor(targetDir, fileName)
        return try {
            moveOrCopyStaging(stagingFile, destination)
            PublishedLocation(
                localUri = Uri.fromFile(destination).toString(),
                filePath = destination.absolutePath,
                finalFileName = destination.name,
                isPrivateStorage = true,
            )
        } catch (_: IOException) {
            if (destination.exists()) destination.delete()
            null
        }
    }

    private fun moveOrCopyStaging(
        stagingFile: File,
        destination: File,
    ) {
        if (stagingFile.renameTo(destination)) return
        stagingFile.inputStream().use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
            }
        }
        stagingFile.delete()
    }

    @Suppress("ReturnCount")
    override fun isFileAvailable(item: DownloadItem): Boolean {
        if (item.status != DownloadStatus.COMPLETED) return false
        item.filePath?.let { path ->
            val file = File(path)
            if (file.exists()) return true
        }
        val uriString = item.localUri ?: return false
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                uri.path?.let { File(it).exists() } == true
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun deleteLocalFile(item: DownloadItem): Boolean {
        deleteStagingFile(item.id)
        val pathDeleted =
            item.filePath?.let(::File)?.takeIf { it.exists() }?.delete()
        val uriDeleted =
            if (pathDeleted == null) {
                item.localUri?.let(::deleteByUri) ?: false
            } else {
                pathDeleted
            }
        return uriDeleted
    }

    private fun deleteByUri(uriString: String): Boolean =
        try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).delete() } == true
                "content" -> context.contentResolver.delete(uri, null, null) > 0
                else -> false
            }
        } catch (_: Exception) {
            false
        }

    fun shareableUri(item: DownloadItem): Uri? {
        val authority = "${context.packageName}.fileprovider"
        val direct = fileProviderUriOrNull(authority, item.filePath)
        val parsed = item.localUri?.let(Uri::parse)
        return direct ?: when (parsed?.scheme) {
            "content" -> parsed
            "file" -> fileProviderUriOrNull(authority, parsed.path)
            else -> null
        }
    }

    private fun fileProviderUriOrNull(
        authority: String,
        path: String?,
    ): Uri? {
        val file = path?.let(::File)?.takeIf { it.exists() } ?: return null
        return try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Suppress("ReturnCount")
    fun openFile(
        activityOrAppContext: Context,
        item: DownloadItem,
    ): OpenFileResult {
        if (!isFileAvailable(item)) return OpenFileResult.FILE_MISSING
        val uri = shareableUri(item) ?: return OpenFileResult.FILE_MISSING
        val mime = guessMimeType(item.fileName, item.mimeType)
        if (tryStartViewIntent(activityOrAppContext, uri, mime)) return OpenFileResult.OPENED
        if (mime != "*/*" && tryStartViewIntent(activityOrAppContext, uri, "*/*")) {
            return OpenFileResult.OPENED
        }
        return OpenFileResult.NO_APP_FOUND
    }

    private fun tryStartViewIntent(
        ctx: Context,
        uri: Uri,
        mime: String,
    ): Boolean {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun shareFile(
        activityOrAppContext: Context,
        item: DownloadItem,
        chooserTitle: String,
    ): Boolean {
        val uri = if (isFileAvailable(item)) shareableUri(item) else null
        if (uri == null) return false
        val mime = guessMimeType(item.fileName, item.mimeType)
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser =
            Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            activityOrAppContext.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openSystemDownloads(ctx: Context): Boolean {
        val intent =
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
