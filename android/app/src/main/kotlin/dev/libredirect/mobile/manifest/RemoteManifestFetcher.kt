package dev.libredirect.mobile.manifest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * HTTPS-only, bounded by size caps regardless of what a server's
 * Content-Length header claims (project plan §34) — the bound is enforced
 * on the actual bytes read, not trusted from a header.
 */
class RemoteManifestFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    suspend fun fetchBytes(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) return@withContext null
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.let { readUpTo(it.byteStream(), maxBytes) }
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
        val chunk = ByteArray(READ_CHUNK_SIZE)
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
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
        const val READ_CHUNK_SIZE = 8_192
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L
        const val DEFAULT_READ_TIMEOUT_SECONDS = 15L

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
