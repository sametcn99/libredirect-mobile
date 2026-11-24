package dev.libredirect.mobile.browser

import dev.libredirect.mobile.core.routing.InstanceHealth
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
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

    private val semaphore = Semaphore(concurrency)

    suspend fun measure(instances: List<String>): Map<String, InstanceHealth> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                instances
                    .map { instance -> async { instance to semaphore.withPermit { probe(instance) } } }
                    .awaitAll()
                    .toMap()
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun probe(instance: String): InstanceHealth =
        try {
            val request = Request.Builder().url(instance)
            val startNanos = System.nanoTime()
            val headStatus = client.newCall(request.head().build()).execute().use { it.code }
            val status =
                if (headStatus == METHOD_NOT_ALLOWED || headStatus == NOT_IMPLEMENTED) {
                    client.newCall(request.get().header("Range", "bytes=0-0").build()).execute().use { it.code }
                } else {
                    headStatus
                }
            val latency =
                if (status in HTTP_SUCCESS) {
                    (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                } else {
                    LATENCY_FAILED
                }
            InstanceHealth(latencyMillis = latency, httpStatus = status, measuredAtMillis = System.currentTimeMillis())
        } catch (_: Exception) {
            InstanceHealth(latencyMillis = LATENCY_FAILED, measuredAtMillis = System.currentTimeMillis())
        }

    companion object {
        private val HTTP_SUCCESS = 200..299
        private const val METHOD_NOT_ALLOWED = 405
        private const val NOT_IMPLEMENTED = 501
        const val TIMEOUT_SECONDS = 5L
        const val PROBE_CONCURRENCY = 16
        const val LATENCY_FAILED = -1L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
