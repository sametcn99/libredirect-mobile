package dev.libredirect.mobile.diagnostics

import java.time.Instant

/**
 * In-memory ring of recent app-side errors (§6 diagnostics screen): no
 * telemetry — the user opens Settings → Diagnostics and copies the buffer
 * into a bug report themselves. Everything in here stays in the process
 * and dies with it.
 */
object DiagnosticsLog {
    private const val CAPACITY = 30

    data class Entry(
        val timestampEpochMillis: Long,
        val tag: String,
        val message: String,
    ) {
        val timestamp: String get() = Instant.ofEpochMilli(timestampEpochMillis).toString()
    }

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(
        tag: String,
        message: String,
    ) {
        entries.addLast(Entry(System.currentTimeMillis(), tag, message.take(MAX_MESSAGE_LENGTH)))
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /** Rendered form for the "copy to clipboard" button. */
    fun asText(): String {
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return "(no recent errors)"
        return snapshot.joinToString("\n") { entry -> "${entry.timestamp} [${entry.tag}] ${entry.message}" }
    }

    @Synchronized
    fun clear() = entries.clear()

    private const val MAX_MESSAGE_LENGTH = 500
}
