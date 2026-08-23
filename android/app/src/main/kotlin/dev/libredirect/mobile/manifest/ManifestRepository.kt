package dev.libredirect.mobile.manifest

import android.content.Context
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.ManifestJson
import dev.libredirect.mobile.core.manifest.ManifestValidator
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.RoutingContext
import dev.libredirect.mobile.core.routing.UrlRouter
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manifest priority (project plan §11/§12): Last Known Good (on-disk
 * `active.json`, then `previous.json`) over the bundled asset. A remote manifest is fetched,
 * signature-verified, decoded, revision-checked, and self-tested — and
 * only written to `active.json` if every one of those steps passes. Any
 * failure leaves the existing active manifest (or the bundled asset, if
 * nothing has ever been activated) untouched and in use.
 */
class ManifestRepository(
    private val context: Context,
    private val fetcher: RemoteManifestFetcher = RemoteManifestFetcher(),
    private val verifier: ManifestVerifier = Ed25519ManifestVerifier,
    private val customServiceRepository: CustomServiceRepository = CustomServiceRepository(context),
) {
    @Volatile
    private var cached: Manifest? = null

    /** Never null in practice — the bundled asset is a build-time guarantee — but
     * callers must still treat a null result as "route nothing, pass everything through"
     * (fail-open) rather than crash, in case that guarantee is ever violated. */
    fun activeManifest(): Manifest? {
        cached?.let { return it.withCustomRoutes() }
        val manifest = loadBaseManifest()
        cached = manifest
        return manifest?.withCustomRoutes()
    }

    fun customRoutes(): List<Route> = customServiceRepository.routes()

    /** Adds a custom route only when it remains valid and does not shadow a built-in host/id. */
    fun addCustomRoute(route: Route): Result<Unit> {
        val base = loadBaseManifest() ?: return Result.failure(IllegalStateException("No routing manifest available"))
        val existing = base.routes + customServiceRepository.routes().filterNot { it.id == route.id }
        val errors = ManifestValidator.validate(base.copy(routes = existing + route))
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("; ")))
        customServiceRepository.save(route)
        return Result.success(Unit)
    }

    fun removeCustomRoute(routeId: String) = customServiceRepository.delete(routeId)

    suspend fun refresh(
        manifestUrl: String,
        signatureUrl: String,
    ): RefreshResult {
        val bundle =
            fetcher.fetchBundle(manifestUrl, signatureUrl)
                ?: return RefreshResult.Rejected(RefreshRejectionReason.FETCH_FAILED)

        if (!verifier.verify(bundle.manifestBytes, bundle.signatureBytes)) {
            return RefreshResult.Rejected(RefreshRejectionReason.INVALID_SIGNATURE)
        }

        val raw = String(bundle.manifestBytes, Charsets.UTF_8)
        val candidate = decodeRaw(raw)
            ?: return RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)

        if (candidate.schemaVersion != ManifestValidator.SUPPORTED_SCHEMA_VERSION) {
            return RefreshResult.Rejected(RefreshRejectionReason.UNSUPPORTED_SCHEMA_VERSION)
        }
        if (ManifestValidator.validate(candidate).isNotEmpty()) {
            return RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)
        }

        val currentRevision = activeManifest()?.revision ?: 0
        if (candidate.revision <= currentRevision) {
            return RefreshResult.NotModified
        }

        if (!selfTest(candidate)) {
            return RefreshResult.Rejected(RefreshRejectionReason.SELF_TEST_FAILED)
        }

        return try {
            activateAtomically(bundle.manifestBytes)
            cached = candidate
            RefreshResult.Activated(candidate.revision)
        } catch (_: IOException) {
            RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)
        }
    }

    /**
     * Best-effort smoke test: a manifest that fails to even construct a
     * [UrlRouter] or resolve a benign URL should never reach `active.json`.
     * This runs after the full structural and semantic validation above as a
     * final defense-in-depth check for runtime construction failures.
     */
    private fun selfTest(manifest: Manifest): Boolean =
        try {
            UrlRouter(manifest).resolve("https://example.org/", RoutingContext())
            true
        } catch (_: RuntimeException) {
            false
        }

    private fun loadActiveFromDisk(): Manifest? = readManifestFile(activeFile())

    private fun loadPreviousFromDisk(): Manifest? = readManifestFile(previousFile())

    private fun loadBundled(): Manifest? =
        try {
            val raw = context.assets.open(BUNDLED_ASSET_NAME).bufferedReader().use { it.readText() }
            decodeAndValidate(raw)
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun loadBaseManifest(): Manifest? = loadActiveFromDisk() ?: loadPreviousFromDisk() ?: loadBundled()

    private fun Manifest.withCustomRoutes(): Manifest {
        val validCustomRoutes = buildList {
            for (route in customServiceRepository.routes()) {
                val candidate = copy(routes = routes + route)
                if (ManifestValidator.validate(candidate).isEmpty()) add(route)
            }
        }
        return if (validCustomRoutes.isEmpty()) this else copy(routes = routes + validCustomRoutes)
    }

    private fun readManifestFile(file: File): Manifest? {
        if (!file.exists()) return null
        return try {
            decodeAndValidate(file.readText())
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun activateAtomically(rawBytes: ByteArray) {
        val dir = manifestDir()
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

    private fun decodeAndValidate(raw: String): Manifest? {
        val manifest = decodeRaw(raw) ?: return null
        return try {
            ManifestValidator.requireValid(manifest)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun decodeRaw(raw: String): Manifest? =
        try {
            ManifestJson.decode(raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun manifestDir(): File = File(context.filesDir, "manifest").apply { mkdirs() }

    private fun activeFile(): File = File(manifestDir(), "active.json")

    private fun previousFile(): File = File(manifestDir(), "previous.json")

    private companion object {
        const val BUNDLED_ASSET_NAME = "routes.json"
    }
}
