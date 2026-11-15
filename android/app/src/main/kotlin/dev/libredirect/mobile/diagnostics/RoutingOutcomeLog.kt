package dev.libredirect.mobile.diagnostics

import dev.libredirect.mobile.core.routing.RoutingResult
import dev.libredirect.mobile.core.url.UrlParser
import java.time.Instant

/**
 * Host-only record of the most recent link-routing outcomes (issue #12
 * follow-up): which host a handled link pointed at and what the router did
 * with it. Deliberately stores NO paths or query strings — the diagnostics
 * screen's "no URLs/history" promise — so patterns like "every link ends in
 * PASSTHROUGH (NO_ROUTE)" or "redirects fail after the unwrap step" become
 * visible in a bug report without leaking the links themselves.
 *
 * Kept in memory for the current process only: it is context, not errors.
 * Errors keep going to [DiagnosticsLog], which persists.
 */
object RoutingOutcomeLog {
    private const val CAPACITY = 15

    data class Entry(
        val timestampEpochMillis: Long,
        val host: String,
        val outcome: String,
        val detail: String? = null,
    ) {
        val timestamp: String get() = Instant.ofEpochMilli(timestampEpochMillis).toString()
    }

    @Volatile
    private var buffer = OutcomeBuffer(CAPACITY)

    /** Records a full resolve result for [url] (host of it is kept, nothing else). */
    fun record(
        url: String,
        result: RoutingResult,
    ): Unit = buffer.record(summaryFor(url, result))

    fun snapshot(): List<Entry> = buffer.snapshot()

    fun asText(): String = buffer.asText()

    fun clear(): Unit = buffer.clear()

    private fun summaryFor(
        url: String,
        result: RoutingResult,
    ): Entry {
        val host = UrlParser.parse(url)?.host ?: "(unparseable)"
        return when (result) {
            is RoutingResult.Redirect ->
                Entry(
                    timestampEpochMillis = System.currentTimeMillis(),
                    host = host,
                    outcome = "REDIRECT",
                    detail =
                        listOfNotNull(
                            "route=${result.routeId}",
                            result.instance?.let { "instance=$it" },
                        ).joinToString(" "),
                )

            is RoutingResult.Passthrough ->
                Entry(
                    timestampEpochMillis = System.currentTimeMillis(),
                    host = host,
                    outcome = "PASSTHROUGH",
                )

            is RoutingResult.Failure ->
                Entry(
                    timestampEpochMillis = System.currentTimeMillis(),
                    host = host,
                    outcome = "FAILURE",
                    detail = "reason=${result.reason}",
                )
        }
    }
}

/** Ring core of [RoutingOutcomeLog], JVM-tested directly. */
internal class OutcomeBuffer(private val capacity: Int) {
    private val entries = ArrayDeque<RoutingOutcomeLog.Entry>()

    @Synchronized
    fun record(entry: RoutingOutcomeLog.Entry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<RoutingOutcomeLog.Entry> = entries.toList()

    fun asText(): String {
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return "(none recorded this session)"
        return snapshot.joinToString("\n") { entry ->
            val detail = entry.detail?.let { " ($it)" }.orEmpty()
            "${entry.timestamp} ${entry.host} -> ${entry.outcome}$detail"
        }
    }

    @Synchronized
    fun clear(): Unit = entries.clear()
}
