package dev.libredirect.mobile.manifest

import dev.libredirect.mobile.core.manifest.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Covers the activation fallback chain (active.json → previous.json →
 * bundled asset) and the atomic activation write. Pure JVM: the store takes
 * its Android pieces (assets, logging) as injectables, so the chain
 * semantics are testable without Robolectric.
 */
class ManifestFileStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val failures = mutableListOf<String>()

    private fun storeFor(
        dir: File,
        bundled: () -> String? = { null },
    ): ManifestFileStore =
        ManifestFileStore(
            manifestDir = dir,
            bundledAssetText = bundled,
            onFailure = { diagnostic, _ -> failures += diagnostic },
        )

    /** Minimal manifest that passes ManifestValidator; revision is the discriminator between sources. */
    private fun manifestJson(revision: Int): String =
        """
        {"schemaVersion":1,"revision":$revision,"generatedAt":"2026-01-01T00:00:00Z","routes":[
            {"id":"test-route","name":"Test Route","hosts":["test.example"],
             "frontends":[{"id":"frontend","name":"Frontend","strategy":{"type":"passthrough"}}]}]}
        """.trimIndent()

    private fun write(
        name: String,
        content: String,
    ): File =
        tempFolder.newFile(name).apply {
            writeText(content)
        }

    private fun revisionOf(manifest: Manifest?): Int? = manifest?.revision

    @Test
    fun `missing files fall back to the bundled asset`() {
        val bundledRevision = 7
        val store = storeFor(tempFolder.root, bundled = { manifestJson(bundledRevision) })

        val loaded = store.loadBase()

        assertEquals(bundledRevision, revisionOf(loaded))
        assertNull(store.lastDiagnostic)
    }

    @Test
    fun `corrupt active json falls back to previous json`() {
        write("active.json", "not json at all")
        val previousRevision = 2
        write("previous.json", manifestJson(previousRevision))
        val store = storeFor(tempFolder.root, bundled = { manifestJson(1) })

        val loaded = store.loadBase()

        assertEquals(previousRevision, revisionOf(loaded))
        assertTrue(store.lastDiagnostic.orEmpty().startsWith("active.json:"))
    }

    @Test
    fun `corrupt active and previous fall back to the bundled asset`() {
        write("active.json", "{")
        write("previous.json", "<html>")
        val bundledRevision = 4
        val store = storeFor(tempFolder.root, bundled = { manifestJson(bundledRevision) })

        val loaded = store.loadBase()

        assertEquals(bundledRevision, revisionOf(loaded))
        assertTrue(failures.any { it.startsWith("active.json:") })
        assertTrue(failures.any { it.startsWith("previous.json:") })
    }

    @Test
    fun `validation-invalid manifests are rejected like undecodable ones`() {
        // Structurally decodable, but semantically invalid: the same host on
        // two routes only fails ManifestValidator, not the decode itself.
        write(
            "active.json",
            """
            {"schemaVersion":1,"revision":9,"generatedAt":"2026-01-01T00:00:00Z","routes":[
                {"id":"r1","name":"R1","hosts":["same.example"],
                 "frontends":[{"id":"f","name":"F","strategy":{"type":"passthrough"}}]},
                {"id":"r2","name":"R2","hosts":["same.example"],
                 "frontends":[{"id":"f","name":"F","strategy":{"type":"passthrough"}}]}]}
            """.trimIndent(),
        )
        val bundledRevision = 3
        val store = storeFor(tempFolder.root, bundled = { manifestJson(bundledRevision) })

        val loaded = store.loadBase()

        assertEquals(bundledRevision, revisionOf(loaded))
        assertTrue(store.lastDiagnostic.orEmpty().contains("validation failed"))
    }

    @Test
    fun `every source unavailable surfaces the last diagnostic`() {
        val store = storeFor(tempFolder.root, bundled = { null })

        assertNull(store.loadBase())

        // The bundled asset's own diagnostic is the most specific one, so it
        // wins over the generic "no valid manifest" fallback.
        assertEquals("routes.json: could not be read (asset missing)", store.lastDiagnostic)
    }

    @Test
    fun `unreadable bundled asset produces its own diagnostic`() {
        val store = storeFor(tempFolder.root, bundled = { null })
        store.loadBase()
        failures.clear()

        // active.json present but unreadable content forces the bundled path to be consulted again.
        write("active.json", "garbage")
        store.loadBase()

        assertTrue(failures.any { it.startsWith("routes.json:") })
    }

    @Test
    fun `activation preserves the outgoing active as previous`() {
        write("active.json", manifestJson(1))
        val store = storeFor(tempFolder.root)

        store.activateAtomically(manifestJson(2).toByteArray())

        assertEquals(2, revisionOf(store.loadBase()))
        val previous =
            ManifestFileStore(manifestDir = tempFolder.root, bundledAssetText = { null }).previousFile().readText()
        assertTrue(previous.contains("\"revision\":1"))
        assertFalse(File(tempFolder.root, "active.json.tmp").exists())
    }

    @Test
    fun `repeated activations advance the chain`() {
        val store = storeFor(tempFolder.root)

        store.activateAtomically(manifestJson(1).toByteArray())
        store.activateAtomically(manifestJson(2).toByteArray())
        store.activateAtomically(manifestJson(3).toByteArray())

        assertEquals(3, revisionOf(store.loadBase()))
        val previousText = store.previousFile().readText()
        assertTrue(previousText.contains("\"revision\":2"))
    }

    @Test
    fun `activation into an empty directory creates no previous`() {
        val store = storeFor(tempFolder.root)

        store.activateAtomically(manifestJson(1).toByteArray())

        assertTrue(store.activeFile().exists())
        assertFalse(store.previousFile().exists())
        assertNotNull(store.loadBase())
    }

    @Test
    fun `activating garbage keeps the previous good manifest reachable`() {
        write("active.json", manifestJson(1))
        val store = storeFor(tempFolder.root, bundled = { manifestJson(1) })

        store.activateAtomically("definitely not json".toByteArray())

        // The garbage active is skipped; previous.json (revision 1) is served.
        assertEquals(1, revisionOf(store.loadBase()))
        assertTrue(store.lastDiagnostic.orEmpty().startsWith("active.json:"))
    }

    @Test
    fun `directory is created on demand by activation`() {
        val nested = File(tempFolder.root, "manifest")
        assertFalse(nested.exists())
        val store = ManifestFileStore(manifestDir = nested, bundledAssetText = { null })

        store.activateAtomically(manifestJson(1).toByteArray())

        assertTrue(store.activeFile().exists())
        assertEquals(1, revisionOf(store.loadBase()))
    }

    @Test
    fun `loadBase resets the diagnostic of a previous failed load`() {
        val store = storeFor(tempFolder.root, bundled = { manifestJson(5) })
        write("active.json", "garbage")
        store.loadBase()
        assertTrue(store.lastDiagnostic != null)

        store.activeFile().delete()

        assertEquals(5, revisionOf(store.loadBase()))
        assertNull(store.lastDiagnostic)
    }

    @Test
    fun `onFailure callback receives every recorded diagnostic`() {
        write("active.json", "nope")
        write("previous.json", "also nope")
        val store = storeFor(tempFolder.root, bundled = { null })

        assertNull(store.loadBase())

        assertEquals(3, failures.size)
    }

    @Test
    fun `temp files do not leak after activation`() {
        val store = storeFor(tempFolder.root)
        val before = Files.list(tempFolder.root.toPath()).use { it.count() }

        store.activateAtomically(manifestJson(1).toByteArray())
        store.activateAtomically(manifestJson(2).toByteArray())

        val after = Files.list(tempFolder.root.toPath()).use { it.count() }
        assertEquals(before + 2, after) // active.json + previous.json only
    }
}
