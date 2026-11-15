package dev.libredirect.mobile.diagnostics

import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * In-memory ring of recent app-side errors (§6 diagnostics screen): no
 * telemetry — the user opens Settings → Diagnostics and copies the buffer
 * into a bug report themselves.
 *
 * v2 lessons from issue #12:
 * - The ring is PERSISTED to a small JSONL file so a crash-recovery restart
 *   no longer destroys the very errors the user is about to report.
 * - Errors record the exception class and the first project frame, not just
 *   the message: `error.message` alone was only accidentally enough to
 *   diagnose the NoSuchMethodError behind issue #12.
 * - Consecutive identical entries collapse into one with a repeat count, so
 *   a looping failure cannot flush 30 distinct errors out of the ring.
 */
object DiagnosticsLog {
    private const val CAPACITY = 30

    @Serializable
    data class Entry(
        val timestampEpochMillis: Long,
        val tag: String,
        val message: String,
        val errorClass: String? = null,
        val repeatCount: Int = 1,
    ) {
        val timestamp: String get() = Instant.ofEpochMilli(timestampEpochMillis).toString()
    }

    @Volatile
    private var buffer: DiagnosticsBuffer = DiagnosticsBuffer(CAPACITY, persistenceFile = null)

    @Volatile
    var startedAtEpochMillis: Long = System.currentTimeMillis()
        private set

    /**
     * Attaches the on-disk persistence file and loads prior entries. Called
     * once from [LibRedirectApplication.onCreate] before anything can fail;
     * passing null keeps the log memory-only (unit tests).
     */
    @Synchronized
    fun install(persistenceFile: File?) {
        startedAtEpochMillis = System.currentTimeMillis()
        buffer = DiagnosticsBuffer(CAPACITY, persistenceFile)
    }

    fun record(
        tag: String,
        message: String,
    ): Unit = buffer.record(tag, message)

    /**
     * Records a throwable with the details that make remote diagnosis
     * possible: exception class, message and the first in-project stack
     * frame, plus an optional context line (e.g. the failing pipeline
     * stage). Safe to call from a crash handler: file I/O failures are
     * swallowed.
     */
    fun record(
        tag: String,
        error: Throwable,
        context: String? = null,
    ): Unit = buffer.record(tag, formatError(error, context))

    @Synchronized
    fun snapshot(): List<Entry> = buffer.snapshot()

    /** Rendered form for the "copy to clipboard" button. */
    fun asText(): String = buffer.asText()

    @Synchronized
    fun clear(): Unit = buffer.clear()

    private fun formatError(
        error: Throwable,
        context: String?,
    ): String {
        val frame =
            error.stackTrace
                .firstOrNull { it.className.startsWith("dev.libredirect.") }
                ?.let { " (${it.fileName}:${it.lineNumber})" }
                .orEmpty()
        val prefix = if (context.isNullOrBlank()) "" else "$context: "
        return "$prefix${error.javaClass.name}: ${error.message}$frame"
    }
}

/**
 * Pure-JVM core of [DiagnosticsLog] (JVM-tested directly): the ring, the
 * consecutive-duplicate collapse and the JSONL persistence. A null
 * [persistenceFile] disables disk writes entirely.
 */
internal class DiagnosticsBuffer(
    private val capacity: Int,
    private val persistenceFile: File?,
) {
    private val entries = ArrayDeque<DiagnosticsLog.Entry>()

    init {
        if (persistenceFile != null) loadAndTrim()
    }

    @Synchronized
    fun record(
        tag: String,
        message: String,
        errorClass: String? = null,
    ) {
        val trimmed = message
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(MAX_MESSAGE_LENGTH)
        val repeat = repeatOfLast(tag, trimmed, errorClass)
        if (repeat != null) {
            entries.removeLast()
            entries.addLast(
                repeat.copy(
                    timestampEpochMillis = System.currentTimeMillis(),
                    repeatCount = repeat.repeatCount + 1,
                ),
            )
        } else {
            entries.addLast(DiagnosticsLog.Entry(System.currentTimeMillis(), tag, trimmed, errorClass))
            while (entries.size > capacity) entries.removeFirst()
        }
        persist()
    }

    @Synchronized
    fun snapshot(): List<DiagnosticsLog.Entry> = entries.toList()

    fun asText(): String {
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return "(no recent errors)"
        return snapshot.joinToString("\n") { entry ->
            val repeated = if (entry.repeatCount > 1) " (x${entry.repeatCount})" else ""
            "${entry.timestamp} [${entry.tag}]${repeated} ${entry.message}"
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persistenceFile?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }
    }

    private fun loadAndTrim() {
        val file = persistenceFile ?: return
        val lines =
            runCatching { if (file.exists()) file.readLines() else emptyList() }
                .getOrDefault(emptyList())
        lines.forEach { line ->
            runCatching { json.decodeFromString(DiagnosticsLog.Entry.serializer(), line) }
                .getOrNull()
                ?.let(entries::addLast)
        }
        while (entries.size > capacity) entries.removeFirst()
        if (lines.size > entries.size) persist()
    }

    private fun repeatOfLast(
        tag: String,
        message: String,
        errorClass: String?,
    ): DiagnosticsLog.Entry? {
        val last = entries.lastOrNull() ?: return null
        return if (last.tag == tag && last.message == message && last.errorClass == errorClass) last else null
    }

    /**
     * The file always mirrors the in-memory ring exactly (rewrite, not
     * append): a collapsed duplicate must not reappear as two entries after
     * a restart, and a crash mid-write can at worst truncate the file, never
     * diverge it from what the user saw.
     */
    private fun persist() {
        val file = persistenceFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                entries.joinToString("") { json.encodeToString(DiagnosticsLog.Entry.serializer(), it) + "\n" },
            )
        }
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 500
        private val json = Json { ignoreUnknownKeys = true }
    }
}
