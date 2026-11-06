package dev.libredirect.mobile.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Measures instance latency (time-to-first-byte of a HEAD request), shared
 * by the browser's instance picker (A3), the service-detail health panel
 * (2.3) and the optional daily background measurement. Bounded concurrency,
 * short timeouts, and a failed probe reports [LATENCY_FAILED] instead of
 * throwing — measurement must never break routing or the UI.
 */
class InstanceProbe(
    timeoutSeconds: Long = TIMEOUT_SECONDS,
    concurrency: Int = PROBE_CONCURRENCY,
) {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

    private val semaphore = Semaphore(concurrency)

    suspend fun measure(instances: List<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                instances
                    .map { instance -> async { instance to semaphore.withPermit { probe(instance) } } }
                    .awaitAll()
                    .toMap()
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun probe(instance: String): Long =
        try {
            val request = Request.Builder().url(instance).head().build()
            val startNanos = System.nanoTime()
            client.newCall(request).execute().use {
                (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            }
        } catch (_: Exception) {
            LATENCY_FAILED
        }

    companion object {
        const val TIMEOUT_SECONDS = 5L
        const val PROBE_CONCURRENCY = 16
        const val LATENCY_FAILED = -1L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
