package dev.libredirect.mobile.manifest

import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.ManifestJson
import dev.libredirect.mobile.core.manifest.ManifestValidator
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The on-disk manifest storage chain (project plan §11/§12): Last Known
 * Good (`active.json`, then `previous.json`) over the bundled asset, plus
 * the atomic activation write. Pure Kotlin — the Android pieces (asset
 * access, logging) are injected as [bundledAssetText] and [onFailure], so
 * the fallback order and activation semantics are unit-testable on the JVM.
 *
 * A failed load never deletes or overwrites the files it could not read; it
 * records a human-readable diagnostic in [lastDiagnostic], which the
 * repository surfaces so the UI can explain an empty service list.
 */
class ManifestFileStore(
    private val manifestDir: File,
    private val bundledAssetText: () -> String?,
    private val onFailure: (diagnostic: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    @Volatile
    var lastDiagnostic: String? = null
        private set

    /** active.json → previous.json → bundled asset; null when nothing valid was found. */
    fun loadBase(): Manifest? {
        lastDiagnostic = null
        val manifest =
            readManifestFile(activeFile(), ACTIVE_FILE_NAME)
                ?: readManifestFile(previousFile(), PREVIOUS_FILE_NAME)
                ?: loadBundled()
        if (manifest == null && lastDiagnostic == null) {
            recordLoadFailure("manifest", "no valid manifest was found", null)
        }
        return manifest
    }

    /**
     * Writes the candidate manifest to active.json atomically, first
     * preserving the outgoing active manifest as previous.json. Throws
     * [IOException] when the disk write fails — in that case previous.json
     * still holds the last fully valid manifest.
     */
    fun activateAtomically(rawBytes: ByteArray) {
        val dir = manifestDir.apply { mkdirs() }
        val active = activeFile()
        if (active.exists()) {
            active.copyTo(previousFile(), overwrite = true)
        }
        val temp = File.createTempFile("active-", ".json.tmp", dir)
        try {
            temp.writeBytes(rawBytes)
            try {
                Files.move(
                    temp.toPath(),
                    active.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    fun activeFile(): File = File(manifestDir, ACTIVE_FILE_NAME)

    fun previousFile(): File = File(manifestDir, PREVIOUS_FILE_NAME)

    private fun loadBundled(): Manifest? =
        try {
            val raw = bundledAssetText() ?: throw IOException("asset missing")
            decodeAndValidate(raw, BUNDLED_ASSET_NAME)
        } catch (error: IOException) {
            recordLoadFailure(BUNDLED_ASSET_NAME, "could not be read", error)
            null
        }

    private fun readManifestFile(
        file: File,
        source: String,
    ): Manifest? {
        if (!file.exists()) return null
        return try {
            decodeAndValidate(file.readText(), source)
        } catch (error: IOException) {
            recordLoadFailure(source, "could not be read", error)
            null
        }
    }

    private fun decodeAndValidate(
        raw: String,
        source: String,
    ): Manifest? {
        val decoded = runCatching { ManifestJson.decode(raw) }
        val decodeError = decoded.exceptionOrNull()
        if (decodeError != null) {
            recordLoadFailure(source, "JSON decode failed", decodeError)
            return null
        }

        val validated = runCatching { ManifestValidator.requireValid(decoded.getOrThrow()) }
        validated.exceptionOrNull()?.let { recordLoadFailure(source, "validation failed", it) }
        return validated.getOrNull()
    }

    private fun recordLoadFailure(
        source: String,
        reason: String,
        error: Throwable?,
    ) {
        val detail = error?.message?.takeIf(String::isNotBlank) ?: error?.javaClass?.simpleName
        lastDiagnostic = "$source: $reason${detail?.let { " ($it)" } ?: ""}"
        onFailure(lastDiagnostic.orEmpty(), error)
    }

    private companion object {
        const val ACTIVE_FILE_NAME = "active.json"
        const val PREVIOUS_FILE_NAME = "previous.json"
        const val BUNDLED_ASSET_NAME = "routes.json"
    }
}
