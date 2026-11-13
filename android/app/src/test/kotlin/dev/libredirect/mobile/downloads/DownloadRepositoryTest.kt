package dev.libredirect.mobile.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DownloadRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var fileStore: FakeDownloadFileStore
    private lateinit var indexFile: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        root = tempFolder.newFolder("downloads-test")
        fileStore = FakeDownloadFileStore(root)
        indexFile = File(root, "downloads_index.json")
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newRepo(
        client: OkHttpClient = OkHttpClient(),
        isUnmetered: () -> Boolean = { true },
    ): DownloadRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return DownloadRepository(
            context = null,
            storage = fileStore,
            client = client,
            scope = scope,
            indexFile = indexFile,
            isUnmeteredNetwork = isUnmetered,
            onStartService = {},
            onPostCompletionNotification = {},
        )
    }

    @Test
    fun `http download completes and cleans up staging file`() {
        val payload = ByteArray(32 * 1024) { (it % 251).toByte() }
        val client =
            clientWithInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", payload.size.toString())
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
        val repo = newRepo(client = client)
        val req = httpRequest("id-1", "sample.bin", payload.size.toLong())

        repo.enqueueHttp(req, "sample.bin", wifiOnly = false)
        val done = awaitItem(repo, "id-1") { it.status == DownloadStatus.COMPLETED }

        assertEquals(payload.size.toLong(), done.bytesDownloaded)
        assertFalse(fileStore.stagingFile("id-1").exists())
        val saved = done.filePath?.let { File(it) }
        assertNotNull(saved)
        assertArrayEquals(payload, saved!!.readBytes())
    }

    @Test
    fun `pause and resume continues via HTTP Range header`() {
        val fullPayload = ByteArray(100 * 1024) { (it % 199).toByte() }
        val firstChunkWritten = CountDownLatch(1)
        val releaseFirstStream = CountDownLatch(1)
        val seenRangeHeader = AtomicBoolean(false)
        val client =
            buildRangeResumeTestClient(
                fullPayload = fullPayload,
                firstChunkWritten = firstChunkWritten,
                releaseFirstStream = releaseFirstStream,
                seenRangeHeader = seenRangeHeader,
            )

        val repo = newRepo(client = client)
        repo.enqueueHttp(httpRequest("id-range", "large.bin", fullPayload.size.toLong()), "large.bin", false)
        assertTrue(firstChunkWritten.await(5, TimeUnit.SECONDS))

        repo.pause("id-range")
        val paused = awaitItem(repo, "id-range") { it.status == DownloadStatus.PAUSED }
        try {
            assertTrue(paused.bytesDownloaded > 0L)
            assertEquals(paused.bytesDownloaded, fileStore.stagingFile("id-range").length())
        } finally {
            releaseFirstStream.countDown()
        }

        repo.resume("id-range")
        val done = awaitItem(repo, "id-range") { it.status == DownloadStatus.COMPLETED }
        assertTrue(seenRangeHeader.get())
        assertArrayEquals(fullPayload, File(done.filePath!!).readBytes())
    }

    private fun buildRangeResumeTestClient(
        fullPayload: ByteArray,
        firstChunkWritten: CountDownLatch,
        releaseFirstStream: CountDownLatch,
        seenRangeHeader: AtomicBoolean,
    ): OkHttpClient {
        val attempt = AtomicInteger(0)
        return clientWithInterceptor { chain ->
            val callNum = attempt.incrementAndGet()
            val req = chain.request()
            if (callNum == 1) {
                val splitAt = 24 * 1024
                val bodySource =
                    blockingChunkSource(
                        firstPart = fullPayload.copyOfRange(0, splitAt),
                        onFirstSent = { firstChunkWritten.countDown() },
                        waitGate = releaseFirstStream,
                        restPart = fullPayload.copyOfRange(splitAt, fullPayload.size),
                    )
                Response
                    .Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", fullPayload.size.toString())
                    .body(
                        bodySource.asResponseBody(
                            "application/octet-stream".toMediaType(),
                            fullPayload.size.toLong(),
                        ),
                    ).build()
            } else {
                val range = req.header("Range")
                seenRangeHeader.set(range?.startsWith("bytes=") == true)
                val offset = range!!.removePrefix("bytes=").removeSuffix("-").toInt()
                val remaining = fullPayload.copyOfRange(offset, fullPayload.size)
                Response
                    .Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $offset-${fullPayload.size - 1}/${fullPayload.size}")
                    .header("Content-Length", remaining.size.toString())
                    .body(remaining.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
        }
    }

    @Test
    fun `http failure records reason and retry recovers`() {
        val payload = "recovered payload".toByteArray(Charsets.UTF_8)
        val calls = AtomicInteger(0)
        val client =
            clientWithInterceptor { chain ->
                if (calls.incrementAndGet() == 1) {
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Unavailable")
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                } else {
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(payload.toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
        val repo = newRepo(client = client)
        repo.enqueueHttp(httpRequest("id-retry", "retry.txt", -1L), "retry.txt", wifiOnly = false)

        val failed = awaitItem(repo, "id-retry") { it.status == DownloadStatus.FAILED }
        assertEquals(DownloadFailureReason.HTTP_ERROR, failed.failureReason)
        assertEquals(503, failed.httpStatusCode)

        repo.retry("id-retry")
        val done = awaitItem(repo, "id-retry") { it.status == DownloadStatus.COMPLETED }
        assertEquals("recovered payload", File(done.filePath!!).readText(Charsets.UTF_8))
    }

    @Test
    fun `wifiOnly waits on metered network until overridden`() {
        val payload = "wifi data".toByteArray()
        val client =
            clientWithInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        val repo = newRepo(client = client, isUnmetered = { false })
        repo.enqueueHttp(httpRequest("id-wifi", "note.txt", -1L), "note.txt", wifiOnly = true)

        val waiting = awaitItem(repo, "id-wifi") { it.status == DownloadStatus.WAITING_FOR_WIFI }
        assertTrue(waiting.wifiOnly)

        repo.resume("id-wifi", allowMeteredOverride = true)
        val done = awaitItem(repo, "id-wifi") { it.status == DownloadStatus.COMPLETED }
        assertFalse(done.wifiOnly)
    }

    @Test
    fun `data uri and blob chunks publish files and persist across reload`() {
        val repo = newRepo()

        val dataBytes = "Hello data URI".toByteArray(Charsets.UTF_8)
        val b64Data = Base64.getEncoder().encodeToString(dataBytes)
        val dataReq =
            PendingDownloadRequest(
                id = "id-data",
                originalUrl = "data:text/plain;base64,$b64Data",
                cleanedUrl = "data:text/plain;base64,$b64Data",
                strippedTrackingCount = 0,
                sourceHost = "example.com",
                sourceKind = DownloadSourceKind.DATA_URI,
                suggestedFileName = "data.txt",
                mimeType = "text/plain",
                contentLength = dataBytes.size.toLong(),
                userAgent = "UA",
                referer = null,
                cookies = null,
                isIncognito = false,
                isPotentiallyDangerous = false,
            )
        repo.enqueueDataUri(dataReq, "data.txt")
        val dataDone = awaitItem(repo, "id-data") { it.status == DownloadStatus.COMPLETED }
        assertEquals("Hello data URI", File(dataDone.filePath!!).readText())

        val blobReq =
            dataReq.copy(
                id = "id-blob",
                originalUrl = "blob:https://example.com/1",
                cleanedUrl = "blob:https://example.com/1",
                sourceKind = DownloadSourceKind.BLOB_URI,
                suggestedFileName = "export.txt",
                isIncognito = true,
            )
        repo.beginBlobDownload(blobReq, "export.txt")
        val chunk1 = Base64.getEncoder().encodeToString("part1-".toByteArray())
        val chunk2 = Base64.getEncoder().encodeToString("part2".toByteArray())
        repo.appendBlobChunk("id-blob", chunk1, isLast = false, totalHintBytes = 11L)
        repo.appendBlobChunk("id-blob", chunk2, isLast = true, totalHintBytes = 11L)
        val blobDone = awaitItem(repo, "id-blob") { it.status == DownloadStatus.COMPLETED }
        assertEquals("part1-part2", File(blobDone.filePath!!).readText())

        repo.clearIncognitoHistory()
        awaitCondition { repo.downloads.value.none { it.id == "id-blob" } }

        val reloaded = newRepo()
        awaitCondition { reloaded.downloads.value.any { it.id == "id-data" } }
        assertFalse(reloaded.downloads.value.any { it.id == "id-blob" })
    }

    @Test
    fun `mediaproxy bin upgrades to mp4 from server Content-Type and Content-Disposition`() {
        val mp4Payload = sampleMp4Payload()
        val proxyUrl = "https://kittygram.nadeko.net/mediaproxy?id=999"

        val videoMimeClient =
            clientWithInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", mp4Payload.size.toString())
                    .body(mp4Payload.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
        val repo1 = newRepo(client = videoMimeClient)
        repo1.enqueueHttp(httpRequest("id-mp-mime", "mediaproxy.bin", -1L, url = proxyUrl), "mediaproxy.bin", false)
        val done1 = awaitItem(repo1, "id-mp-mime") { it.status == DownloadStatus.COMPLETED }
        assertEquals("mediaproxy.mp4", done1.fileName)
        assertEquals("video/mp4", done1.mimeType)
        assertTrue(done1.filePath!!.endsWith("mediaproxy.mp4"))

        val dispositionClient =
            clientWithInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Disposition", "attachment; filename=\"reel_dtqr.mp4\"")
                    .body(mp4Payload.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
        val repo2 = newRepo(client = dispositionClient)
        repo2.enqueueHttp(httpRequest("id-mp-disp", "mediaproxy.bin", -1L, url = proxyUrl), "mediaproxy.bin", false)
        val done2 = awaitItem(repo2, "id-mp-disp") { it.status == DownloadStatus.COMPLETED }
        assertEquals("reel_dtqr.mp4", done2.fileName)
        assertEquals("video/mp4", done2.mimeType)
    }

    @Test
    fun `mediaproxy bin sniffs MP4 magic bytes when server returns octet-stream`() {
        val mp4Payload = sampleMp4Payload()
        val octetSniffClient =
            clientWithInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", mp4Payload.size.toString())
                    .body(mp4Payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
        val repo = newRepo(client = octetSniffClient)
        val proxyUrl = "https://kittygram.nadeko.net/mediaproxy?id=999"
        repo.enqueueHttp(httpRequest("id-mp-sniff", "mediaproxy.bin", -1L, url = proxyUrl), "mediaproxy.bin", false)
        val done = awaitItem(repo, "id-mp-sniff") { it.status == DownloadStatus.COMPLETED }
        assertEquals("mediaproxy.mp4", done.fileName)
        assertEquals("video/mp4", done.mimeType)
    }

    private fun sampleMp4Payload(): ByteArray {
        val header = ByteArray(16)
        "ftypisom".encodeToByteArray().copyInto(header, destinationOffset = 4)
        return header + ByteArray(512) { 0x11 }
    }

    @Test
    fun `probeHttpMetadata resolves video type and full length via Range GET`() =
        runBlocking {
            val sampleHead = ByteArray(64)
            "ftypisom".encodeToByteArray().copyInto(sampleHead, destinationOffset = 4)
            val probeClient =
                clientWithInterceptor { chain ->
                    assertEquals("bytes=0-63", chain.request().header("Range"))
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 0-63/6426042")
                        .body(sampleHead.toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            val repo = newRepo(client = probeClient)
            val initial = httpRequest("id-probe", "mediaproxy.bin", -1L, url = "https://kittygr.am/mediaproxy?id=1")
            val updated = repo.probeHttpMetadata(initial)
            assertNotNull(updated)
            assertEquals("mediaproxy.mp4", updated!!.suggestedFileName)
            assertEquals("video/mp4", updated.mimeType)
            assertEquals(6426042L, updated.contentLength)
        }

    private fun httpRequest(
        id: String,
        fileName: String,
        size: Long,
        url: String = "https://cdn.example.com/$fileName?utm_source=x",
        cleanedUrl: String = url.substringBefore("?utm_source"),
    ): PendingDownloadRequest =
        PendingDownloadRequest(
            id = id,
            originalUrl = url,
            cleanedUrl = cleanedUrl,
            strippedTrackingCount = 1,
            sourceHost = "cdn.example.com",
            sourceKind = DownloadSourceKind.HTTP,
            suggestedFileName = fileName,
            mimeType = "application/octet-stream",
            contentLength = size,
            userAgent = "TestAgent",
            referer = "https://example.com/",
            cookies = "session=secret",
            isIncognito = false,
            isPotentiallyDangerous = false,
        )

    private fun clientWithInterceptor(handler: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handler(chain) }).build()

    private fun blockingChunkSource(
        firstPart: ByteArray,
        onFirstSent: () -> Unit,
        waitGate: CountDownLatch,
        restPart: ByteArray,
    ): BufferedSource {
        val buffer = Buffer().apply { write(firstPart) }
        var sentFirst = false
        val base =
            object : ForwardingSource(buffer) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val count = super.read(sink, byteCount)
                    if (count == -1L && !sentFirst) {
                        sentFirst = true
                        onFirstSent()
                        waitGate.await(5, TimeUnit.SECONDS)
                        buffer.write(restPart)
                        return super.read(sink, byteCount)
                    }
                    return count
                }
            }
        return base.buffer()
    }

    private fun awaitItem(
        repo: DownloadRepository,
        id: String,
        predicate: (DownloadItem) -> Boolean,
    ): DownloadItem {
        awaitCondition {
            val found = repo.downloads.value.find { it.id == id }
            found != null && predicate(found)
        }
        return repo.downloads.value.first { it.id == id }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        assertTrue("Timed out waiting for condition", condition())
    }
}

private class FakeDownloadFileStore(
    root: File,
) : DownloadFileStore {
    private val stagingDir = File(root, "staging").apply { mkdirs() }
    private val publishedDir = File(root, "published").apply { mkdirs() }

    override fun stagingFile(id: String): File {
        stagingDir.mkdirs()
        return File(stagingDir, "$id.part")
    }

    override fun deleteStagingFile(id: String) {
        File(stagingDir, "$id.part").delete()
    }

    override fun publishCompletedFile(
        stagingFile: File,
        desiredName: String,
        mimeType: String,
    ): PublishedLocation? {
        if (!stagingFile.exists()) return null
        val target = uniqueFileFor(publishedDir, sanitizeFileName(desiredName, mimeType))
        if (!stagingFile.renameTo(target)) {
            stagingFile.copyTo(target, overwrite = true)
            stagingFile.delete()
        }
        return PublishedLocation(
            localUri = target.toURI().toString(),
            filePath = target.absolutePath,
            finalFileName = target.name,
            isPrivateStorage = false,
        )
    }

    override fun isFileAvailable(item: DownloadItem): Boolean =
        item.status == DownloadStatus.COMPLETED && item.filePath?.let { File(it).exists() } == true

    override fun deleteLocalFile(item: DownloadItem): Boolean {
        deleteStagingFile(item.id)
        return item.filePath?.let { File(it).delete() } ?: false
    }
}
