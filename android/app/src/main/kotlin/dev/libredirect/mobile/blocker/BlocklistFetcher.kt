package dev.libredirect.mobile.blocker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads the remote blocklist from one fixed HTTPS source (4.2). The
 * stream is read with a hard byte cap, then validated by the same
 * parse-and-count step used for the bundled list before activation — a bad
 * or oversized download never reaches disk.
 */
class BlocklistFetcher(
    private val client: OkHttpClient = defaultClient(DEFAULT_TIMEOUT_SECONDS),
) {
    constructor(timeoutSeconds: Long) : this(defaultClient(timeoutSeconds))

    @Suppress("TooGenericExceptionCaught")
    suspend fun fetch(url: String = BlocklistEndpoints.REMOTE_URL): String? =
        withContext(Dispatchers.IO) {
            try {
                client
                    .newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@use null
                        val source = response.body?.source() ?: return@use null
                        // If request() returns true for MAX_BYTES + 1, the stream exceeds the cap.
                        if (source.request(ContentBlocker.MAX_BYTES + 1)) return@use null
                        val text = source.readUtf8()
                        if (text.toByteArray(Charsets.UTF_8).size > ContentBlocker.MAX_BYTES) return@use null
                        if (ContentBlocker.parseHosts(text).isEmpty()) return@use null
                        text
                    }
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        fun defaultClient(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
    }
}

/** The single fixed remote source for blocklist updates (hosts-file format). */
object BlocklistEndpoints {
    const val REMOTE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
}
