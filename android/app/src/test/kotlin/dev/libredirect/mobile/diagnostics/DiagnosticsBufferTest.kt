package dev.libredirect.mobile.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsBufferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `consecutive identical entries collapse into one with a repeat count`() {
        val buffer = DiagnosticsBuffer(capacity = 5, persistenceFile = null)
        repeat(3) { buffer.record("RedirectActivity", "same failure") }
        val snapshot = buffer.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(3, snapshot[0].repeatCount)
        assertTrue(buffer.asText().contains("(x3)"))
    }

    @Test
    fun `a different entry breaks the collapse run`() {
        val buffer = DiagnosticsBuffer(capacity = 5, persistenceFile = null)
        buffer.record("T", "failure A")
        buffer.record("T", "failure B")
        buffer.record("T", "failure A")
        assertEquals(3, buffer.snapshot().size)
        assertEquals(listOf(1, 1, 1), buffer.snapshot().map { it.repeatCount })
    }

    @Test
    fun `ring keeps only the newest capacity entries`() {
        val buffer = DiagnosticsBuffer(capacity = 3, persistenceFile = null)
        (1..10).forEach { buffer.record("T", "entry $it") }
        assertEquals(3, buffer.snapshot().size)
        assertEquals("entry 10", buffer.snapshot().last().message)
    }

    @Test
    fun `dedup survives a restart`() {
        val file = File(tmp.root, "diagnostics/recent-errors.jsonl")
        val first = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        first.record("T", "looping failure")
        first.record("T", "looping failure")

        val reloaded = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        reloaded.record("T", "looping failure")
        val snapshot = reloaded.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(3, snapshot[0].repeatCount)
    }

    @Test
    fun `entries survive a restart by reloading the persistence file`() {
        val file = File(tmp.root, "diagnostics/recent-errors.jsonl")
        val first = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        first.record("RedirectActivity", "routing failed at stage=resolve: boom")
        first.record("Downloads", "Download failed: host=example.org reason=NETWORK_ERROR")

        val reloaded = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        val snapshot = reloaded.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("routing failed at stage=resolve: boom", snapshot[0].message)
        assertEquals("Downloads", snapshot[1].tag)
    }

    @Test
    fun `corrupt persistence lines are skipped without losing the rest`() {
        val file = File(tmp.root, "diagnostics/recent-errors.jsonl")
        file.parentFile?.mkdirs()
        file.writeText("this is not json\n" + DiagnosticsBufferJson.lineFor("T", "good entry") + "\n")

        val reloaded = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        val snapshot = reloaded.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("good entry", snapshot[0].message)
    }

    @Test
    fun `persistence file mirrors the ring and never grows unbounded`() {
        val file = File(tmp.root, "diagnostics/recent-errors.jsonl")
        val buffer = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        repeat(120) { buffer.record("T", "entry $it") }

        val lines = file.readLines().size
        assertEquals(5, lines)

        // A reload caps at capacity and rewrites the file to match exactly.
        val reloaded = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        assertEquals(5, reloaded.snapshot().size)
        assertEquals(5, file.readLines().size)
    }

    @Test
    fun `clear removes the entries and deletes the persistence file`() {
        val file = File(tmp.root, "diagnostics/recent-errors.jsonl")
        val buffer = DiagnosticsBuffer(capacity = 5, persistenceFile = file)
        buffer.record("T", "entry")
        assertTrue(file.exists())

        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `multiline messages are flattened to a single line`() {
        val buffer = DiagnosticsBuffer(capacity = 5, persistenceFile = null)
        buffer.record("T", "line one\nline two\twith tab")
        val message = buffer.snapshot().single().message
        assertFalse(message.contains('\n'))
        assertFalse(message.contains('\t'))
    }

    @Test
    fun `oversized messages are truncated to the cap`() {
        val buffer = DiagnosticsBuffer(capacity = 5, persistenceFile = null)
        buffer.record("T", "x".repeat(2000))
        assertEquals(500, buffer.snapshot().single().message.length)
    }
}

/** Exposes the exact JSONL encoding the buffer writes, for fixture tests. */
internal object DiagnosticsBufferJson {
    fun lineFor(
        tag: String,
        message: String,
    ): String =
        kotlinx.serialization.json.Json.encodeToString(
            DiagnosticsLog.Entry.serializer(),
            DiagnosticsLog.Entry(0L, tag, message, null, 1),
        )
}

class DiagnosticsLogTest {
    @Test
    fun `throwable records carry context class message and first project frame`() {
        DiagnosticsLog.install(null)
        val error =
            IllegalStateException("boom").apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("java.lang.Thread", "run", "Thread.java", 700),
                        StackTraceElement("dev.libredirect.mobile.core.url.Foo", "bar", "Foo.kt", 42),
                        StackTraceElement("dev.libredirect.mobile.other.Baz", "qux", "Baz.kt", 7),
                    )
            }

        DiagnosticsLog.record("RedirectActivity", error, "routing failed at stage=resolve")

        val message = DiagnosticsLog.snapshot().single().message
        assertTrue(message.startsWith("routing failed at stage=resolve:"))
        assertTrue(message.contains("java.lang.IllegalStateException: boom"))
        // The first in-project frame wins over JDK frames.
        assertTrue(message.contains("(Foo.kt:42)"))
        assertTrue(!message.contains("Thread.java"))
    }
}
