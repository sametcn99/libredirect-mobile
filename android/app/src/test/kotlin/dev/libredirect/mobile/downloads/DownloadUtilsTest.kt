package dev.libredirect.mobile.downloads

import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.settings.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Locale

class DownloadUtilsTest {
    @Test
    fun `sanitizeFileName removes illegal characters and supplies extension from mime`() {
        val cleaned = sanitizeFileName("report:q4*draft?.txt", "text/plain")
        assertEquals("report_q4_draft_.txt", cleaned)

        val strippedPath = sanitizeFileName("../../etc/draft?.txt", "text/plain")
        assertEquals("draft_.txt", strippedPath)

        val inferred = sanitizeFileName("archive_export", "application/pdf")
        assertEquals("archive_export.pdf", inferred)

        val fallback = sanitizeFileName("   ", "image/png")
        assertEquals("download.png", fallback)
    }

    @Test
    fun `sanitizeFileName bounds very long names while preserving extension`() {
        val longBase = "a".repeat(200)
        val result = sanitizeFileName("$longBase.pdf", "application/pdf")
        assertTrue(result.length <= 140)
        assertTrue(result.endsWith(".pdf"))
    }

    @Test
    fun `ensureFileExtension keeps original extension when user edits only stem`() {
        assertEquals("my_notes.pdf", ensureFileExtension("my_notes", "original.pdf", "application/pdf"))
        assertEquals("custom.txt", ensureFileExtension("custom.txt", "original.pdf", "application/pdf"))
        assertEquals("archive.bin", ensureFileExtension("archive.bin", "video.mp4", "video/mp4"))
        assertEquals("mediaproxy.mp4", ensureFileExtension("mediaproxy.bin", "mediaproxy.bin", "video/mp4"))
        assertEquals("my_clip.mp4", ensureFileExtension("my_clip", "mediaproxy.bin", "video/mp4"))
        assertEquals("firmware.bin", ensureFileExtension("firmware.bin", "firmware.bin", "application/octet-stream"))
    }

    @Test
    fun `resolveInitialHttpDownloadMetadata unwraps Kittygram mediaproxy video and image URLs`() {
        val videoProxyUrl =
            "https://kittygram.nadeko.net/mediaproxy?url=" +
                "https%3A%2F%2Finstagram.fscl26-1.fna.fbcdn.net%2Fo1%2Fv%2Ft2%2Ff2%2Fm86%2F" +
                "AQPks_reel_clip.mp4%3F_nc_cat%3D104%26ccb%3D17-1"
        val resolvedVideo =
            resolveInitialHttpDownloadMetadata(
                url = videoProxyUrl,
                contentDisposition = null,
                declaredMimeType = "application/octet-stream",
                platformGuess = "mediaproxy.bin",
            )
        assertEquals("AQPks_reel_clip.mp4", resolvedVideo.fileName)
        assertEquals("video/mp4", resolvedVideo.mimeType)

        val posterProxyUrl =
            "https://kittygr.am/mediaproxy?url=" +
                "https%3A%2F%2Fscontent.cdninstagram.com%2Fv%2Fcover_photo.jpg%3Fstp%3Ddst-jpg"
        val resolvedImage =
            resolveInitialHttpDownloadMetadata(
                url = posterProxyUrl,
                contentDisposition = null,
                declaredMimeType = null,
                platformGuess = "mediaproxy.bin",
            )
        assertEquals("cover_photo.jpg", resolvedImage.fileName)
        assertEquals("image/jpeg", resolvedImage.mimeType)

        val phpProxyUrl =
            "https://proxy.example.org/proxy.php?url=https%3A%2F%2Fcdn.example.org%2Fclip_2.webm%3Ftoken%3D1"
        val resolvedPhp =
            resolveInitialHttpDownloadMetadata(
                url = phpProxyUrl,
                contentDisposition = null,
                declaredMimeType = null,
                platformGuess = "proxy.php",
            )
        assertEquals("clip_2.webm", resolvedPhp.fileName)
        assertEquals("video/webm", resolvedPhp.mimeType)
    }

    @Test
    fun `parseContentDispositionFileName and resolveServerDownloadMetadata handle headers and magic bytes`() {
        val dispositionHeader =
            "attachment; filename*=UTF-8''kitty%20clip+1.mp4; filename=\"fallback.mp4\""
        assertEquals(
            "kitty clip+1.mp4",
            parseContentDispositionFileName(dispositionHeader),
        )
        assertEquals(
            "safe_clip.mp4",
            parseContentDispositionFileName("inline; filename=\"../../dir/safe_clip.mp4\""),
        )

        val byHeaderMime =
            resolveServerDownloadMetadata(
                url = "https://kittygr.am/mediaproxy?id=42",
                currentFileName = "mediaproxy.bin",
                currentMimeType = "application/octet-stream",
                contentDisposition = null,
                serverContentType = "video/mp4",
            )
        assertEquals("mediaproxy.mp4", byHeaderMime.fileName)
        assertEquals("video/mp4", byHeaderMime.mimeType)

        val byDisposition =
            resolveServerDownloadMetadata(
                url = "https://kittygr.am/mediaproxy?id=42",
                currentFileName = "mediaproxy.bin",
                currentMimeType = "application/octet-stream",
                contentDisposition = "attachment; filename=\"insta_reel_42.mp4\"",
                serverContentType = "application/octet-stream",
            )
        assertEquals("insta_reel_42.mp4", byDisposition.fileName)
        assertEquals("video/mp4", byDisposition.mimeType)

        val userRenamed =
            resolveServerDownloadMetadata(
                url = "https://kittygr.am/mediaproxy?id=42",
                currentFileName = "my_custom_reel.bin",
                currentMimeType = "application/octet-stream",
                contentDisposition = "attachment; filename=\"server_random_name.mp4\"",
                serverContentType = "video/mp4",
            )
        assertEquals("my_custom_reel.mp4", userRenamed.fileName)
        assertEquals("video/mp4", userRenamed.mimeType)

        val htmlChallengeIgnored =
            resolveServerDownloadMetadata(
                url = "https://kittygram.nadeko.net/mediaproxy?id=42",
                currentFileName = "mediaproxy.bin",
                currentMimeType = "application/octet-stream",
                contentDisposition = null,
                serverContentType = "text/html; charset=utf-8",
            )
        assertEquals("mediaproxy.bin", htmlChallengeIgnored.fileName)
        assertEquals("application/octet-stream", htmlChallengeIgnored.mimeType)

        val mp4Header =
            byteArrayOf(0x00, 0x00, 0x00, 0x20) + "ftypisom".toByteArray(Charsets.US_ASCII)
        val pngHeader =
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("video/mp4", sniffMimeTypeFromBytes(mp4Header))
        assertEquals("image/png", sniffMimeTypeFromBytes(pngHeader))
        assertEquals("application/pdf", sniffMimeTypeFromBytes("%PDF-1.7".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `categorizeDownload classifies extensions and mime types`() {
        assertEquals(DownloadCategory.IMAGES, categorizeDownload("photo.webp", "image/webp"))
        assertEquals(DownloadCategory.VIDEOS, categorizeDownload("clip.mp4", null))
        assertEquals(DownloadCategory.AUDIO, categorizeDownload("track.opus", null))
        assertEquals(DownloadCategory.DOCUMENTS, categorizeDownload("manual.pdf", null))
        assertEquals(DownloadCategory.ARCHIVES_AND_APPS, categorizeDownload("app.apk", null))
        assertNull(categorizeDownload("firmware.bin", "application/octet-stream"))
    }

    @Test
    fun `isPotentiallyDangerousFile flags executables and installers`() {
        assertTrue(isPotentiallyDangerousFile("release.apk", null))
        assertTrue(isPotentiallyDangerousFile("download.bin", "application/vnd.android.package-archive"))
        assertTrue(isPotentiallyDangerousFile("script.sh", "text/plain"))
        assertFalse(isPotentiallyDangerousFile("document.pdf", "application/pdf"))
        assertFalse(isPotentiallyDangerousFile("picture.png", "image/png"))
    }

    @Test
    fun `formatByteSize and etaParts format values accurately`() {
        assertEquals("512 B", formatByteSize(512, Locale.US))
        assertEquals("1.5 KB", formatByteSize(1536, Locale.US))
        assertEquals("2.0 MB", formatByteSize(2L * 1024L * 1024L, Locale.US))
        assertEquals(EtaParts.Seconds(45), etaParts(45))
        assertEquals(EtaParts.Minutes(2, 15), etaParts(135))
        assertEquals(EtaParts.Hours(1, 5), etaParts(3900))
    }

    @Test
    fun `filterDownloads matches category and search query`() {
        val sample =
            listOf(
                sampleItem("1", "vacation.jpg", "image/jpeg", DownloadStatus.COMPLETED, "cdn.example.com"),
                sampleItem("2", "build.zip", "application/zip", DownloadStatus.RUNNING, "releases.dev"),
                sampleItem("3", "broken.pdf", "application/pdf", DownloadStatus.FAILED, "docs.org"),
            )

        assertEquals(listOf("2"), filterDownloads(sample, "", DownloadCategory.ACTIVE).map { it.id })
        assertEquals(listOf("1"), filterDownloads(sample, "", DownloadCategory.IMAGES).map { it.id })
        assertEquals(listOf("3"), filterDownloads(sample, "docs.org", DownloadCategory.FAILED).map { it.id })
        assertEquals(listOf("1"), filterDownloads(sample, "vac", DownloadCategory.ALL).map { it.id })
    }

    @Test
    fun `parseDataUri handles base64 and percent encoded payloads`() {
        val raw = "Hello LibRedirect+".toByteArray(Charsets.UTF_8)
        val b64 = Base64.getEncoder().encodeToString(raw)
        val decodedB64 = parseDataUri("data:text/plain;base64,$b64")
        assertNotNull(decodedB64)
        assertEquals("text/plain", decodedB64?.mimeType)
        assertArrayEquals(raw, decodedB64?.bytes)

        val decodedText = parseDataUri("data:text/plain;charset=utf-8,1+1%3D2")
        assertNotNull(decodedText)
        assertEquals("1+1=2", decodedText?.bytes?.toString(Charsets.UTF_8))

        assertNull(parseDataUri("https://example.com/file.zip"))
    }

    @Test
    fun `prepareDownloadUrl strips tracking params and respects exceptions`() {
        val prepared =
            prepareDownloadUrl(
                "https://files.example.org/app.apk?utm_source=feed&id=42",
                AppSettings(),
            )
        assertEquals("https://files.example.org/app.apk?id=42", prepared.cleanedUrl)
        assertEquals(1, prepared.strippedCount)
        assertEquals("files.example.org", prepared.sourceHost)

        val excepted =
            prepareDownloadUrl(
                "https://files.example.org/app.apk?utm_source=feed&id=42",
                AppSettings(
                    exceptions = listOf(ExceptionRule.Domain("files.example.org")),
                ),
            )
        assertEquals("https://files.example.org/app.apk?utm_source=feed&id=42", excepted.cleanedUrl)
        assertEquals(0, excepted.strippedCount)
    }

    private fun sampleItem(
        id: String,
        fileName: String,
        mime: String,
        status: DownloadStatus,
        host: String,
    ): DownloadItem =
        DownloadItem(
            id = id,
            fileName = fileName,
            originalUrl = "https://$host/$fileName",
            cleanedUrl = "https://$host/$fileName",
            sourceHost = host,
            mimeType = mime,
            status = status,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
}
