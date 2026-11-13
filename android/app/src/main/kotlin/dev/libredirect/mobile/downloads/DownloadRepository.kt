package dev.libredirect.mobile.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val MAX_CONCURRENT_DOWNLOADS = 2
private const val MAX_HISTORY_ITEMS = 200
private const val PROGRESS_EMIT_INTERVAL_MS = 250L
private const val PERSIST_INTERVAL_MS = 2_000L
private const val MILLIS_PER_SEC = 1_000L
private const val STREAM_BUFFER_BYTES = 16 * 1024
private const val CONNECT_TIMEOUT_SECS = 20L
private const val READ_TIMEOUT_SECS = 30L
private const val PROBE_TIMEOUT_SECS = 4L
private const val PROBE_SNIFF_BYTES = 64
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val INDEX_FILE_NAME = "downloads_index.json"

@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class DownloadRepository(
    private val context: Context?,
    private val storage: DownloadFileStore = DownloadStorage(requireNotNull(context)),
    private val client: OkHttpClient = defaultClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val indexFile: File = File(requireNotNull(context).filesDir, INDEX_FILE_NAME),
    private val isUnmeteredNetwork: () -> Boolean = { context == null || checkUnmeteredNetwork(context) },
    private val onStartService: () -> Unit = { context?.let { DownloadService.start(it) } },
    onPostCompletionNotification: ((DownloadItem) -> Unit)? = null,
) {
    private val notificationHelper: DownloadNotificationHelper? =
        context?.let(::DownloadNotificationHelper)
    private val onPostCompletionNotification: (DownloadItem) -> Unit =
        onPostCompletionNotification ?: { item -> notificationHelper?.postCompletionNotification(item) }
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val stateMutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val controlOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val blobOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val ephemeralCookiesById = ConcurrentHashMap<String, String>()
    private val blobWriters = ConcurrentHashMap<String, BlobStreamState>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        scope.launch {
            loadInitialIndex()
            runScheduleNext()
            for (op in controlOps) {
                op()
            }
        }
        scope.launch {
            loaded.await()
            for (op in blobOps) {
                op()
            }
        }
    }

    private suspend fun loadInitialIndex() {
        val initial =
            try {
                if (indexFile.exists()) {
                    val raw = indexFile.readText(Charsets.UTF_8)
                    json.decodeFromString<List<DownloadItem>>(raw).map { normalizeRestoredItem(it) }
                } else {
                    emptyList()
                }
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            } catch (_: IOException) {
                emptyList()
            }
        val sorted = initial.sortedByDescending { it.createdAtEpochMillis }
        updateDownloadsState(persist = true) { sorted }
        loaded.complete(Unit)
    }

    private fun normalizeRestoredItem(item: DownloadItem): DownloadItem =
        when {
            item.sourceKind == DownloadSourceKind.BLOB_URI && item.status.isInProgressOrPaused -> {
                storage.deleteStagingFile(item.id)
                item.copy(
                    status = DownloadStatus.FAILED,
                    speedBytesPerSec = 0L,
                    etaSeconds = null,
                    failureReason = DownloadFailureReason.BLOB_ERROR,
                )
            }
            item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.QUEUED ->
                item.copy(
                    status = DownloadStatus.PAUSED,
                    speedBytesPerSec = 0L,
                    etaSeconds = null,
                )
            else -> item
        }

    fun enqueueHttp(
        request: PendingDownloadRequest,
        fileName: String,
        wifiOnly: Boolean,
    ): String {
        val id = request.id
        val finalName = ensureFileExtension(fileName, request.suggestedFileName, request.mimeType)
        val resolvedMime = guessMimeType(finalName, request.mimeType)
        val now = System.currentTimeMillis()
        val item =
            DownloadItem(
                id = id,
                fileName = finalName,
                originalUrl = request.originalUrl,
                cleanedUrl = request.cleanedUrl,
                strippedTrackingCount = request.strippedTrackingCount,
                sourceHost = request.sourceHost,
                sourceKind = DownloadSourceKind.HTTP,
                mimeType = resolvedMime,
                status = DownloadStatus.QUEUED,
                totalBytes = request.contentLength,
                isIncognito = request.isIncognito,
                wifiOnly = wifiOnly,
                userAgent = request.userAgent,
                referer = request.referer,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        request.cookies?.takeIf { it.isNotBlank() }?.let { ephemeralCookiesById[id] = it }
        controlOps.trySend {
            insertItem(item)
            _events.tryEmit(DownloadEvent.Started(item))
            runScheduleNext()
        }
        return id
    }

    /**
     * Performs a short ranged GET probe to resolve real filename, MIME type,
     * and byte size from servers/proxies before the user confirms a download.
     */
    @Suppress("ReturnCount")
    suspend fun probeHttpMetadata(request: PendingDownloadRequest): PendingDownloadRequest? {
        if (request.sourceKind != DownloadSourceKind.HTTP) return null
        return withContext(Dispatchers.IO) {
            val probeClient = client.newBuilder().callTimeout(PROBE_TIMEOUT_SECS, TimeUnit.SECONDS).build()
            val httpReq =
                buildHttpRequest(
                    url = request.cleanedUrl,
                    userAgent = request.userAgent,
                    referer = request.referer,
                    cookies = if (request.isIncognito) null else request.cookies,
                    rangeHeader = "bytes=0-${PROBE_SNIFF_BYTES - 1}",
                )
            val call = probeClient.newCall(httpReq)
            try {
                call.execute().use { resp -> applyProbeResponse(request, resp) }
            } catch (cancelled: CancellationException) {
                call.cancel()
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    @Suppress("ReturnCount")
    private fun applyProbeResponse(
        request: PendingDownloadRequest,
        resp: Response,
    ): PendingDownloadRequest? {
        if (!resp.isSuccessful) return null
        val body = resp.body ?: return null
        val contentRangeTotal =
            if (resp.code == HTTP_PARTIAL_CONTENT) {
                parseContentRangeTotalBytes(resp.header("Content-Range"))
            } else {
                null
            }
        val rawBodyLength = body.contentLength().takeIf { it > 0L }
        val totalLength =
            contentRangeTotal
                ?: if (resp.code != HTTP_PARTIAL_CONTENT) {
                    rawBodyLength ?: request.contentLength
                } else {
                    request.contentLength
                }
        val leadingBytes = readLeadingBytes(body, PROBE_SNIFF_BYTES)
        val sniffedMime = sniffMimeTypeFromBytes(leadingBytes)
        val resolved =
            resolveServerDownloadMetadata(
                url = request.cleanedUrl,
                currentFileName = request.suggestedFileName,
                currentMimeType = request.mimeType,
                contentDisposition = resp.header("Content-Disposition"),
                serverContentType = resp.header("Content-Type"),
                sniffedMimeType = sniffedMime,
            )
        val finalLength = if (totalLength > 0L) totalLength else request.contentLength
        val changed =
            resolved.fileName != request.suggestedFileName ||
                resolved.mimeType != request.mimeType ||
                finalLength != request.contentLength
        if (!changed) return null
        return request.copy(
            suggestedFileName = resolved.fileName,
            mimeType = resolved.mimeType,
            contentLength = finalLength,
            isPotentiallyDangerous = isPotentiallyDangerousFile(resolved.fileName, resolved.mimeType),
        )
    }

    fun enqueueDataUri(
        request: PendingDownloadRequest,
        fileName: String,
    ): String {
        val id = request.id
        val finalName = ensureFileExtension(fileName, request.suggestedFileName, request.mimeType)
        val resolvedMime = guessMimeType(finalName, request.mimeType)
        val now = System.currentTimeMillis()
        val item =
            DownloadItem(
                id = id,
                fileName = finalName,
                originalUrl = request.originalUrl,
                cleanedUrl = request.cleanedUrl,
                sourceHost = request.sourceHost,
                sourceKind = DownloadSourceKind.DATA_URI,
                mimeType = resolvedMime,
                status = DownloadStatus.RUNNING,
                totalBytes = request.contentLength,
                isIncognito = request.isIncognito,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        controlOps.trySend {
            insertItem(item)
            _events.tryEmit(DownloadEvent.Started(item))
            scope.launch { executeDataUriSave(id, request.originalUrl, finalName, resolvedMime) }
        }
        return id
    }

    private suspend fun executeDataUriSave(
        id: String,
        dataUri: String,
        fileName: String,
        fallbackMime: String,
    ) {
        val decoded = parseDataUri(dataUri)
        if (decoded == null) {
            markFailed(id, DownloadFailureReason.INVALID_URL, null)
            return
        }
        val staging = storage.stagingFile(id)
        try {
            staging.outputStream().use { it.write(decoded.bytes) }
            val mime = guessMimeType(fileName, decoded.mimeType.ifBlank { fallbackMime })
            val published = storage.publishCompletedFile(staging, fileName, mime)
            if (published == null) {
                storage.deleteStagingFile(id)
                markFailed(id, DownloadFailureReason.STORAGE_ERROR, null)
                return
            }
            markCompleted(id, published, decoded.bytes.size.toLong(), mime)
        } catch (_: IOException) {
            storage.deleteStagingFile(id)
            markFailed(id, DownloadFailureReason.STORAGE_ERROR, null)
        }
    }

    fun beginBlobDownload(
        request: PendingDownloadRequest,
        fileName: String,
    ): String {
        val id = request.id
        val finalName = ensureFileExtension(fileName, request.suggestedFileName, request.mimeType)
        val resolvedMime = guessMimeType(finalName, request.mimeType)
        val now = System.currentTimeMillis()
        val staging = storage.stagingFile(id)
        if (staging.exists()) staging.delete()
        blobWriters[id] =
            BlobStreamState(
                file = staging,
                startedAtMillis = now,
                lastEmitMillis = 0L,
            )
        val item =
            DownloadItem(
                id = id,
                fileName = finalName,
                originalUrl = request.originalUrl,
                cleanedUrl = request.cleanedUrl,
                sourceHost = request.sourceHost,
                sourceKind = DownloadSourceKind.BLOB_URI,
                mimeType = resolvedMime,
                status = DownloadStatus.RUNNING,
                totalBytes = request.contentLength,
                isIncognito = request.isIncognito,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        blobOps.trySend {
            insertItem(item)
            _events.tryEmit(DownloadEvent.Started(item))
            onStartService()
        }
        return id
    }

    fun appendBlobChunk(
        id: String,
        base64Chunk: String,
        isLast: Boolean,
        totalHintBytes: Long,
    ) {
        blobOps.trySend {
            val state = blobWriters[id]
            val current = findItem(id)
            if (state == null || current == null) return@trySend
            if (current.status == DownloadStatus.CANCELED) {
                blobWriters.remove(id)
                storage.deleteStagingFile(id)
                return@trySend
            }
            try {
                if (base64Chunk.isNotEmpty()) {
                    val bytes = Base64.getDecoder().decode(base64Chunk)
                    FileOutputStream(state.file, true).use { it.write(bytes) }
                    state.bytesWritten += bytes.size.toLong()
                }
                val now = System.currentTimeMillis()
                val total = if (totalHintBytes > 0L) totalHintBytes else current.totalBytes
                if (isLast) {
                    blobWriters.remove(id)
                    val published = storage.publishCompletedFile(state.file, current.fileName, current.mimeType)
                    if (published == null) {
                        storage.deleteStagingFile(id)
                        markFailed(id, DownloadFailureReason.STORAGE_ERROR, null)
                    } else {
                        markCompleted(id, published, state.bytesWritten, current.mimeType)
                    }
                } else if (now - state.lastEmitMillis >= PROGRESS_EMIT_INTERVAL_MS) {
                    state.lastEmitMillis = now
                    val elapsed = (now - state.startedAtMillis).coerceAtLeast(1L)
                    val speed = (state.bytesWritten * MILLIS_PER_SEC) / elapsed
                    val eta = estimateEtaSeconds(state.bytesWritten, total, speed)
                    updateItem(id, persist = false) { item ->
                        item.copy(
                            bytesDownloaded = state.bytesWritten,
                            totalBytes = total,
                            speedBytesPerSec = speed,
                            etaSeconds = eta,
                            updatedAtEpochMillis = now,
                        )
                    }
                }
            } catch (_: Exception) {
                blobWriters.remove(id)
                storage.deleteStagingFile(id)
                markFailed(id, DownloadFailureReason.BLOB_ERROR, null)
            }
        }
    }

    fun failBlobDownload(
        id: String,
        detail: String?,
    ) {
        blobOps.trySend {
            blobWriters.remove(id)
            storage.deleteStagingFile(id)
            markFailed(id, DownloadFailureReason.BLOB_ERROR, detail)
        }
    }

    @Suppress("LongParameterList")
    fun recordImmediateFailure(
        fileName: String,
        url: String,
        sourceHost: String,
        sourceKind: DownloadSourceKind,
        mimeType: String,
        isIncognito: Boolean,
        reason: DownloadFailureReason,
        detail: String? = null,
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val item =
            DownloadItem(
                id = id,
                fileName = sanitizeFileName(fileName, mimeType),
                originalUrl = url,
                cleanedUrl = url,
                sourceHost = sourceHost,
                sourceKind = sourceKind,
                mimeType = mimeType,
                status = DownloadStatus.FAILED,
                isIncognito = isIncognito,
                failureReason = reason,
                failureDetail = detail,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        controlOps.trySend {
            insertItem(item)
            _events.tryEmit(DownloadEvent.Failed(item))
        }
        return id
    }

    fun pause(id: String) {
        controlOps.trySend {
            val item = findItem(id) ?: return@trySend
            if (item.sourceKind != DownloadSourceKind.HTTP) return@trySend
            if (!item.status.isActive && item.status != DownloadStatus.WAITING_FOR_WIFI) return@trySend
            signalCancelActiveTransfer(id)
            val staged = stagedByteCount(id)
            updateItem(id, persist = true) {
                it.copy(
                    status = DownloadStatus.PAUSED,
                    bytesDownloaded = maxOf(it.bytesDownloaded, staged),
                    speedBytesPerSec = 0L,
                    etaSeconds = null,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
            }
            runScheduleNext()
        }
    }

    fun pauseAll() {
        controlOps.trySend {
            val toPause =
                _downloads.value
                    .filter { it.sourceKind == DownloadSourceKind.HTTP && it.status.isActive }
                    .map { it.id }
            if (toPause.isEmpty()) return@trySend
            toPause.forEach(::signalCancelActiveTransfer)
            val now = System.currentTimeMillis()
            val stagedById = toPause.associateWith(::stagedByteCount)
            updateDownloadsState(persist = true) { list ->
                list.map { item ->
                    if (item.id in toPause) {
                        item.copy(
                            status = DownloadStatus.PAUSED,
                            bytesDownloaded = maxOf(item.bytesDownloaded, stagedById[item.id] ?: 0L),
                            speedBytesPerSec = 0L,
                            etaSeconds = null,
                            updatedAtEpochMillis = now,
                        )
                    } else {
                        item
                    }
                }
            }
        }
    }

    fun resume(
        id: String,
        allowMeteredOverride: Boolean = false,
    ) {
        controlOps.trySend {
            val item = findItem(id) ?: return@trySend
            if (item.sourceKind != DownloadSourceKind.HTTP) return@trySend
            if (item.status == DownloadStatus.COMPLETED || item.status.isActive) return@trySend
            updateItem(id, persist = true) {
                it.copy(
                    status = DownloadStatus.QUEUED,
                    wifiOnly = if (allowMeteredOverride) false else it.wifiOnly,
                    failureReason = DownloadFailureReason.NONE,
                    failureDetail = null,
                    httpStatusCode = null,
                    speedBytesPerSec = 0L,
                    etaSeconds = null,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
            }
            runScheduleNext()
        }
    }

    fun retry(id: String) {
        resume(id, allowMeteredOverride = false)
    }

    fun cancel(id: String) {
        controlOps.trySend {
            findItem(id) ?: return@trySend
            signalCancelActiveTransfer(id)
            updateItem(id, persist = true) {
                it.copy(
                    status = DownloadStatus.CANCELED,
                    bytesDownloaded = 0L,
                    speedBytesPerSec = 0L,
                    etaSeconds = null,
                    failureReason = DownloadFailureReason.USER_CANCELED,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
            }
            ephemeralCookiesById.remove(id)
            blobWriters.remove(id)
            if (!activeJobs.containsKey(id)) {
                storage.deleteStagingFile(id)
            }
            runScheduleNext()
        }
    }

    fun cancelAllActive() {
        controlOps.trySend {
            val activeIds =
                _downloads.value
                    .filter { it.status.isActive || it.status == DownloadStatus.WAITING_FOR_WIFI }
                    .map { it.id }
            activeIds.forEach(::signalCancelActiveTransfer)
            val now = System.currentTimeMillis()
            updateDownloadsState(persist = true) { list ->
                list.map { item ->
                    if (item.id in activeIds) {
                        item.copy(
                            status = DownloadStatus.CANCELED,
                            bytesDownloaded = 0L,
                            speedBytesPerSec = 0L,
                            etaSeconds = null,
                            failureReason = DownloadFailureReason.USER_CANCELED,
                            updatedAtEpochMillis = now,
                        )
                    } else {
                        item
                    }
                }
            }
            activeIds.forEach { id ->
                ephemeralCookiesById.remove(id)
                blobWriters.remove(id)
                if (!activeJobs.containsKey(id)) {
                    storage.deleteStagingFile(id)
                }
            }
            unregisterWifiCallbackIfUnused()
        }
    }

    fun removeEntry(
        id: String,
        deleteFile: Boolean,
    ) {
        controlOps.trySend {
            val item = findItem(id) ?: return@trySend
            signalCancelActiveTransfer(id)
            ephemeralCookiesById.remove(id)
            blobWriters.remove(id)
            if (!activeJobs.containsKey(id)) {
                storage.deleteStagingFile(id)
            }
            if (deleteFile) {
                storage.deleteLocalFile(item)
            }
            updateDownloadsState(persist = true) { list -> list.filterNot { it.id == id } }
            runScheduleNext()
        }
    }

    fun clearFinished(deleteFiles: Boolean) {
        controlOps.trySend {
            val removable = _downloads.value.filter { !it.status.isInProgressOrPaused }
            if (removable.isEmpty()) return@trySend
            if (deleteFiles) {
                removable.forEach { storage.deleteLocalFile(it) }
            }
            val removeIds = removable.map { it.id }.toSet()
            updateDownloadsState(persist = true) { list -> list.filterNot { it.id in removeIds } }
        }
    }

    fun clearIncognitoHistory() {
        controlOps.trySend {
            val removable = _downloads.value.filter { it.isIncognito && !it.status.isActive }
            if (removable.isEmpty()) return@trySend
            removable.forEach { item ->
                ephemeralCookiesById.remove(item.id)
                if (item.status != DownloadStatus.COMPLETED && !activeJobs.containsKey(item.id)) {
                    storage.deleteStagingFile(item.id)
                }
            }
            val removeIds = removable.map { it.id }.toSet()
            updateDownloadsState(persist = true) { list -> list.filterNot { it.id in removeIds } }
            unregisterWifiCallbackIfUnused()
        }
    }

    fun refreshFileAvailability() {
        controlOps.trySend {
            val snapshot = _downloads.value.filter { it.status == DownloadStatus.COMPLETED }
            if (snapshot.isEmpty()) return@trySend
            val missingIds = snapshot.filter { !storage.isFileAvailable(it) }.map { it.id }.toSet()
            updateDownloadsState(persist = false) { list ->
                list.map { item ->
                    if (item.status == DownloadStatus.COMPLETED) {
                        val missing = item.id in missingIds
                        if (item.fileMissing != missing) item.copy(fileMissing = missing) else item
                    } else {
                        item
                    }
                }
            }
        }
    }

    private suspend fun runScheduleNext() {
        val currentList = _downloads.value
        val activeCount = activeJobs.values.count { it.isActive }
        val slots = (MAX_CONCURRENT_DOWNLOADS - activeCount).coerceAtLeast(0)
        if (slots > 0) {
            val queuedHttp =
                currentList
                    .filter {
                        it.sourceKind == DownloadSourceKind.HTTP &&
                            it.status == DownloadStatus.QUEUED &&
                            !activeJobs.containsKey(it.id)
                    }.sortedBy { it.createdAtEpochMillis }
            var launched = 0
            for (candidate in queuedHttp) {
                if (launched >= slots) break
                if (candidate.wifiOnly && !isUnmeteredNetwork()) {
                    updateItem(candidate.id, persist = true) {
                        it.copy(
                            status = DownloadStatus.WAITING_FOR_WIFI,
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        )
                    }
                    ensureWifiCallbackRegistered()
                } else {
                    launchHttpJob(candidate.id)
                    launched++
                }
            }
        }
        if (_downloads.value.any { it.status.isActive }) {
            onStartService()
        }
        unregisterWifiCallbackIfUnused()
    }

    private fun launchHttpJob(id: String) {
        if (activeJobs.containsKey(id)) return
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runHttpTransfer(id)
                } finally {
                    withContext(NonCancellable) {
                        activeJobs.remove(id, job)
                        controlOps.trySend {
                            cleanupStagingIfEnded(id)
                            runScheduleNext()
                        }
                    }
                }
            }
        activeJobs[id] = job
        job.start()
    }

    private fun cleanupStagingIfEnded(id: String) {
        if (activeJobs.containsKey(id)) return
        val item = findItem(id)
        if (item == null || item.status == DownloadStatus.CANCELED) {
            storage.deleteStagingFile(id)
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private suspend fun runHttpTransfer(id: String) {
        val initial = findItem(id) ?: return
        val nowStart = System.currentTimeMillis()
        updateItem(id, persist = true) {
            it.copy(
                status = DownloadStatus.RUNNING,
                speedBytesPerSec = 0L,
                etaSeconds = null,
                updatedAtEpochMillis = nowStart,
            )
        }
        onStartService()

        val staging = storage.stagingFile(id)
        var existingOffset = if (staging.exists()) staging.length() else 0L
        var callAndResp = executeHttpCall(id, initial, existingOffset) ?: return
        if (callAndResp.second.code == HTTP_RANGE_NOT_SATISFIABLE && existingOffset > 0L) {
            callAndResp.second.close()
            activeCalls.remove(id, callAndResp.first)
            storage.deleteStagingFile(id)
            existingOffset = 0L
            callAndResp = executeHttpCall(id, initial, 0L) ?: return
        }

        val (activeCall, response) = callAndResp
        try {
            response.use { resp ->
                if (!resp.isSuccessful) {
                    ephemeralCookiesById.remove(id)
                    markFailed(
                        id = id,
                        reason = DownloadFailureReason.HTTP_ERROR,
                        detail = "HTTP ${resp.code}",
                        httpStatusCode = resp.code,
                    )
                    return
                }
                val body = resp.body
                if (body == null) {
                    markFailed(id, DownloadFailureReason.NETWORK_ERROR, "Empty response body")
                    return
                }

                val isPartial = resp.code == HTTP_PARTIAL_CONTENT && existingOffset > 0L
                val append = isPartial
                val startOffset = if (isPartial) existingOffset else 0L
                if (!append && staging.exists()) {
                    staging.delete()
                }
                val contentLength = body.contentLength()
                val contentRangeTotal =
                    if (isPartial) {
                        parseContentRangeTotalBytes(resp.header("Content-Range"))
                    } else {
                        null
                    }
                val totalBytes =
                    when {
                        contentRangeTotal != null -> contentRangeTotal
                        contentLength >= 0L -> startOffset + contentLength
                        else -> initial.totalBytes
                    }
                val contentDisposition = resp.header("Content-Disposition")
                val serverContentType = resp.header("Content-Type")
                val headerResolved =
                    resolveServerDownloadMetadata(
                        url = initial.cleanedUrl,
                        currentFileName = initial.fileName,
                        currentMimeType = initial.mimeType,
                        contentDisposition = contentDisposition,
                        serverContentType = serverContentType,
                    )
                val metadataChanged =
                    headerResolved.fileName != initial.fileName || headerResolved.mimeType != initial.mimeType

                val writtenTotal =
                    streamResponseBody(
                        id = id,
                        body = body,
                        staging = staging,
                        append = append,
                        startOffset = startOffset,
                        totalBytes = totalBytes,
                        resolvedMetadata = headerResolved,
                        persistMetadata = metadataChanged,
                    ) ?: return

                val latest = findItem(id) ?: return
                if (latest.status != DownloadStatus.RUNNING) return
                val finalMetadata =
                    finalizeServerMetadata(
                        latest = latest,
                        staging = staging,
                        headerResolved = headerResolved,
                        contentDisposition = contentDisposition,
                        serverContentType = serverContentType,
                    )
                val published = storage.publishCompletedFile(staging, finalMetadata.fileName, finalMetadata.mimeType)
                if (published == null) {
                    markFailed(id, DownloadFailureReason.STORAGE_ERROR, null, bytesDownloaded = writtenTotal)
                    return
                }
                ephemeralCookiesById.remove(id)
                markCompleted(id, published, writtenTotal, finalMetadata.mimeType)
            }
        } finally {
            activeCalls.remove(id, activeCall)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun streamResponseBody(
        id: String,
        body: okhttp3.ResponseBody,
        staging: File,
        append: Boolean,
        startOffset: Long,
        totalBytes: Long,
        resolvedMetadata: ResolvedDownloadMetadata,
        persistMetadata: Boolean,
    ): Long? {
        var writtenTotal = startOffset
        updateItem(id, persist = persistMetadata) {
            it.copy(
                fileName = resolvedMetadata.fileName,
                bytesDownloaded = startOffset,
                totalBytes = totalBytes,
                mimeType = resolvedMetadata.mimeType,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }
        return try {
            body.byteStream().use { input ->
                FileOutputStream(staging, append).use { output ->
                    writtenTotal =
                        copyStreamWithProgress(
                            id = id,
                            input = input,
                            output = output,
                            startOffset = startOffset,
                            totalBytes = totalBytes,
                        )
                    output.flush()
                }
            }
            writtenTotal
        } catch (cancelled: CancellationException) {
            val status = findItem(id)?.status
            if (status == DownloadStatus.PAUSED || status == DownloadStatus.QUEUED) {
                withContext(NonCancellable) {
                    syncPausedBytes(id, maxOf(writtenTotal, stagedByteCount(id)))
                }
            }
            throw cancelled
        } catch (io: IOException) {
            val status = findItem(id)?.status
            val actualBytes = maxOf(writtenTotal, stagedByteCount(id))
            if (status == DownloadStatus.RUNNING) {
                markFailed(id, DownloadFailureReason.NETWORK_ERROR, io.message, bytesDownloaded = actualBytes)
            } else if (status == DownloadStatus.PAUSED || status == DownloadStatus.QUEUED) {
                syncPausedBytes(id, actualBytes)
            }
            null
        }
    }

    private fun stagedByteCount(id: String): Long =
        storage.stagingFile(id).takeIf { it.exists() }?.length() ?: 0L

    private suspend fun syncPausedBytes(
        id: String,
        bytes: Long,
    ) {
        val current = findItem(id)
        val canSync = current?.status == DownloadStatus.PAUSED || current?.status == DownloadStatus.QUEUED
        if (canSync && bytes > current.bytesDownloaded) {
            updateItem(id, persist = true) { item ->
                val stillCanSync = item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.QUEUED
                if (stillCanSync && bytes > item.bytesDownloaded) {
                    item.copy(bytesDownloaded = bytes, updatedAtEpochMillis = System.currentTimeMillis())
                } else {
                    item
                }
            }
        }
    }

    private suspend fun copyStreamWithProgress(
        id: String,
        input: java.io.InputStream,
        output: FileOutputStream,
        startOffset: Long,
        totalBytes: Long,
    ): Long {
        var writtenTotal = startOffset
        var windowBytes = 0L
        var windowStartMs = System.currentTimeMillis()
        var lastEmitMs = windowStartMs
        var lastPersistMs = windowStartMs
        val buffer = ByteArray(STREAM_BUFFER_BYTES)

        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            currentCoroutineContext().ensureActive()
            if (read < 0) break
            output.write(buffer, 0, read)
            writtenTotal += read
            windowBytes += read

            val now = System.currentTimeMillis()
            if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                val elapsed = (now - windowStartMs).coerceAtLeast(1L)
                val speed = (windowBytes * MILLIS_PER_SEC) / elapsed
                val eta = estimateEtaSeconds(writtenTotal, totalBytes, speed)
                val shouldPersist = now - lastPersistMs >= PERSIST_INTERVAL_MS
                updateItem(id, persist = shouldPersist) { item ->
                    item.copy(
                        bytesDownloaded = writtenTotal,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speed,
                        etaSeconds = eta,
                        updatedAtEpochMillis = now,
                    )
                }
                lastEmitMs = now
                if (shouldPersist) lastPersistMs = now
                if (elapsed >= PERSIST_INTERVAL_MS) {
                    windowBytes = 0L
                    windowStartMs = now
                }
            }
        }
        return writtenTotal
    }

    private fun finalizeServerMetadata(
        latest: DownloadItem,
        staging: File,
        headerResolved: ResolvedDownloadMetadata,
        contentDisposition: String?,
        serverContentType: String?,
    ): ResolvedDownloadMetadata {
        val needsSniff =
            isGenericMimeType(headerResolved.mimeType) ||
                fileExtensionOf(latest.fileName).let { it.isEmpty() || it == "bin" }
        val sniffedMime = if (needsSniff) sniffMimeTypeFromFile(staging) else null
        return resolveServerDownloadMetadata(
            url = latest.cleanedUrl,
            currentFileName = latest.fileName,
            currentMimeType = headerResolved.mimeType,
            contentDisposition = contentDisposition,
            serverContentType = serverContentType,
            sniffedMimeType = sniffedMime,
        )
    }

    private fun readLeadingBytes(
        body: okhttp3.ResponseBody,
        maxBytes: Int,
    ): ByteArray =
        try {
            body.byteStream().use { input -> readLeadingStreamBytes(input, maxBytes) }
        } catch (_: IOException) {
            ByteArray(0)
        }

    private fun buildHttpRequest(
        url: String,
        userAgent: String?,
        referer: String?,
        cookies: String?,
        rangeHeader: String? = null,
    ): Request {
        val builder = Request.Builder().url(url)
        userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        cookies?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        rangeHeader?.let { builder.header("Range", it) }
        return builder.build()
    }

    private suspend fun executeHttpCall(
        id: String,
        item: DownloadItem,
        offset: Long,
    ): Pair<Call, Response>? {
        val rangeHeader = if (offset > 0L) "bytes=$offset-" else null
        val request =
            buildHttpRequest(
                url = item.cleanedUrl,
                userAgent = item.userAgent,
                referer = item.referer,
                cookies = ephemeralCookiesById[id],
                rangeHeader = rangeHeader,
            )
        val call = client.newCall(request)
        activeCalls[id] = call
        return try {
            currentCoroutineContext().ensureActive()
            call to call.execute()
        } catch (cancelled: CancellationException) {
            call.cancel()
            activeCalls.remove(id, call)
            throw cancelled
        } catch (io: IOException) {
            activeCalls.remove(id, call)
            val latest = findItem(id)
            if (latest?.status == DownloadStatus.RUNNING) {
                markFailed(id, DownloadFailureReason.NETWORK_ERROR, io.message, bytesDownloaded = offset)
            }
            null
        } catch (illegal: IllegalArgumentException) {
            activeCalls.remove(id, call)
            markFailed(id, DownloadFailureReason.INVALID_URL, illegal.message)
            null
        }
    }

    private fun signalCancelActiveTransfer(id: String) {
        activeCalls[id]?.cancel()
        activeJobs[id]?.cancel()
    }

    private suspend fun insertItem(item: DownloadItem) {
        updateDownloadsState(persist = true) { current ->
            (listOf(item) + current.filterNot { it.id == item.id }).take(MAX_HISTORY_ITEMS)
        }
    }

    private suspend fun updateItem(
        id: String,
        persist: Boolean,
        transform: (DownloadItem) -> DownloadItem,
    ) {
        updateDownloadsState(persist = persist) { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private suspend fun markCompleted(
        id: String,
        published: PublishedLocation,
        finalSize: Long,
        mimeType: String,
    ) {
        val now = System.currentTimeMillis()
        var completedItem: DownloadItem? = null
        updateDownloadsState(persist = true) { list ->
            list.map { item ->
                if (item.id == id) {
                    item.copy(
                        fileName = published.finalFileName,
                        mimeType = mimeType,
                        status = DownloadStatus.COMPLETED,
                        bytesDownloaded = finalSize,
                        totalBytes = finalSize,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        localUri = published.localUri,
                        filePath = published.filePath,
                        isPrivateStorage = published.isPrivateStorage,
                        failureReason = DownloadFailureReason.NONE,
                        failureDetail = null,
                        fileMissing = false,
                        updatedAtEpochMillis = now,
                        completedAtEpochMillis = now,
                    ).also { completedItem = it }
                } else {
                    item
                }
            }
        }
        completedItem?.let {
            _events.tryEmit(DownloadEvent.Completed(it))
            onPostCompletionNotification(it)
        }
    }

    private suspend fun markFailed(
        id: String,
        reason: DownloadFailureReason,
        detail: String?,
        httpStatusCode: Int? = null,
        bytesDownloaded: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        var failedItem: DownloadItem? = null
        updateDownloadsState(persist = true) { list ->
            list.map { item ->
                if (item.id == id) {
                    item.copy(
                        status = DownloadStatus.FAILED,
                        bytesDownloaded = bytesDownloaded ?: item.bytesDownloaded,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        httpStatusCode = httpStatusCode ?: item.httpStatusCode,
                        failureReason = reason,
                        failureDetail = detail,
                        updatedAtEpochMillis = now,
                    ).also { failedItem = it }
                } else {
                    item
                }
            }
        }
        failedItem?.let {
            _events.tryEmit(DownloadEvent.Failed(it))
            onPostCompletionNotification(it)
        }
    }

    private fun findItem(id: String): DownloadItem? = _downloads.value.find { it.id == id }

    private suspend fun updateDownloadsState(
        persist: Boolean,
        transform: (List<DownloadItem>) -> List<DownloadItem>,
    ) {
        stateMutex.withLock {
            val updated = transform(_downloads.value)
            if (!persist) {
                _downloads.value = updated
                return@withLock
            }
            try {
                indexFile.parentFile?.mkdirs()
                val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
                temp.writeText(json.encodeToString(updated), Charsets.UTF_8)
                if (!temp.renameTo(indexFile)) {
                    temp.copyTo(indexFile, overwrite = true)
                    temp.delete()
                }
            } catch (_: IOException) {
            }
            _downloads.value = updated
        }
    }

    private fun ensureWifiCallbackRegistered() {
        val cm =
            if (networkCallback == null) {
                context?.getSystemService(ConnectivityManager::class.java)
            } else {
                null
            } ?: return
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    requeueWifiWaitingDownloads()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                        requeueWifiWaitingDownloads()
                    }
                }
            }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {
        }
    }

    private fun unregisterWifiCallbackIfUnused() {
        val hasWaiting = _downloads.value.any { it.status == DownloadStatus.WAITING_FOR_WIFI }
        if (hasWaiting) return
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            context?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun requeueWifiWaitingDownloads() {
        if (!isUnmeteredNetwork()) return
        controlOps.trySend {
            val waitingIds =
                _downloads.value
                    .filter { it.status == DownloadStatus.WAITING_FOR_WIFI }
                    .map { it.id }
            if (waitingIds.isEmpty()) return@trySend
            val now = System.currentTimeMillis()
            updateDownloadsState(persist = true) { list ->
                list.map { item ->
                    if (item.id in waitingIds) {
                        item.copy(status = DownloadStatus.QUEUED, updatedAtEpochMillis = now)
                    } else {
                        item
                    }
                }
            }
            runScheduleNext()
        }
    }

    private class BlobStreamState(
        val file: File,
        val startedAtMillis: Long,
        var lastEmitMillis: Long,
        var bytesWritten: Long = 0L,
    )

    companion object {
        private fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        private fun checkUnmeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
            return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }
    }
}
