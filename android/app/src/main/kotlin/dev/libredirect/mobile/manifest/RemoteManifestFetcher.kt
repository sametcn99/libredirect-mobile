package dev.libredirect.mobile.manifest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class RemoteManifestBundle(
    val manifestBytes: ByteArray,
    val signatureBytes: ByteArray,
)

/**
 * HTTPS-only, bounded by size caps regardless of what a server's
 * Content-Length header claims (project plan §34) — the bound is enforced
 * on the actual bytes read, not trusted from a header.
 */
class RemoteManifestFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val maxManifestBytes: Long = 5L * 1024 * 1024,
    private val maxSignatureBytes: Long = 4L * 1024,
) {
    suspend fun fetchBundle(
        manifestUrl: String,
        signatureUrl: String,
    ): RemoteManifestBundle? =
        withContext(Dispatchers.IO) {
            val manifestBytes = fetchBytesBlocking(manifestUrl, maxManifestBytes) ?: return@withContext null
            val signatureBytes = fetchBytesBlocking(signatureUrl, maxSignatureBytes) ?: return@withContext null
            RemoteManifestBundle(manifestBytes, signatureBytes)
        }

    private fun fetchBytesBlocking(
        url: String,
        maxBytes: Long,
    ): ByteArray? {
        if (!url.startsWith("https://")) return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                readUpTo(body.byteStream(), maxBytes)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun readUpTo(
        stream: InputStream,
        limit: Long,
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read == -1) break
            total += read
            if (total > limit) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}
