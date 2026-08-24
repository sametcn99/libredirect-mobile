package dev.libredirect.mobile.manifest

import android.content.Context
import android.util.Log
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.ManifestJson
import dev.libredirect.mobile.core.manifest.ManifestValidator
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.RoutingContext
import dev.libredirect.mobile.core.routing.UrlRouter
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

    @Volatile
    private var lastLoadError: String? = null

    /** Never null in practice — the bundled asset is a build-time guarantee — but
     * callers must still treat a null result as "route nothing, pass everything through"
     * (fail-open) rather than crash, in case that guarantee is ever violated. */
    fun activeManifest(): Manifest? {
        cached?.let { return it.withCustomRoutes() }
        val manifest = loadBaseManifest()
        cached = manifest
        return manifest?.withCustomRoutes()
    }

    /**
     * Returns a diagnostic for the last failed load, if no valid manifest was
     * available. The detail is intentionally kept here so the UI can explain
     * an empty service list instead of silently treating it as a valid state.
     */
    fun lastLoadError(): String? = lastLoadError

    fun customRoutes(): List<Route> = customServiceRepository.routes()

    /** Adds a custom route only when it remains valid and does not shadow a built-in host/id. */
    fun addCustomRoute(route: Route): Result<Unit> {
        val base = loadBaseManifest()
        return if (base == null) {
            Result.failure(IllegalStateException("No routing manifest available"))
        } else {
            val existing = base.routes + customServiceRepository.routes().filterNot { it.id == route.id }
            val errors = ManifestValidator.validate(base.copy(routes = existing + route))
            if (errors.isNotEmpty()) {
                Result.failure(IllegalArgumentException(errors.joinToString("; ")))
            } else {
                customServiceRepository.save(route)
                Result.success(Unit)
            }
        }
    }

    fun removeCustomRoute(routeId: String) = customServiceRepository.delete(routeId)

    suspend fun refresh(
        manifestUrl: String,
        signatureUrl: String,
    ): RefreshResult {
        val bundle = fetcher.fetchBundle(manifestUrl, signatureUrl)
        return if (bundle == null) {
            RefreshResult.Rejected(RefreshRejectionReason.FETCH_FAILED)
        } else if (!verifier.verify(bundle.manifestBytes, bundle.signatureBytes)) {
            RefreshResult.Rejected(RefreshRejectionReason.INVALID_SIGNATURE)
        } else {
            refreshVerifiedBundle(bundle)
        }
    }

    private fun refreshVerifiedBundle(bundle: RemoteManifestBundle): RefreshResult {
        val candidate = decodeRaw(String(bundle.manifestBytes, Charsets.UTF_8))
        return when {
            candidate == null -> RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)
            candidate.schemaVersion != ManifestValidator.SUPPORTED_SCHEMA_VERSION ->
                RefreshResult.Rejected(RefreshRejectionReason.UNSUPPORTED_SCHEMA_VERSION)
            ManifestValidator.validate(candidate).isNotEmpty() ->
                RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)
            candidate.revision <= (activeManifest()?.revision ?: 0) -> RefreshResult.NotModified
            !selfTest(candidate) -> RefreshResult.Rejected(RefreshRejectionReason.SELF_TEST_FAILED)
            else -> activateCandidate(bundle.manifestBytes, candidate)
        }
    }

    private fun activateCandidate(
        rawBytes: ByteArray,
        candidate: Manifest,
    ): RefreshResult =
        try {
            activateAtomically(rawBytes)
            cached = candidate
            RefreshResult.Activated(candidate.revision)
        } catch (_: IOException) {
            RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)
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

    private fun loadActiveFromDisk(): Manifest? = readManifestFile(activeFile(), "active.json")

    private fun loadPreviousFromDisk(): Manifest? = readManifestFile(previousFile(), "previous.json")

    private fun loadBundled(): Manifest? =
        try {
            val raw = context.assets.open(BUNDLED_ASSET_NAME).bufferedReader().use { it.readText() }
            decodeAndValidate(raw, BUNDLED_ASSET_NAME)
        } catch (error: IOException) {
            recordLoadFailure(BUNDLED_ASSET_NAME, "could not be read", error)
            null
        }

    private fun loadBaseManifest(): Manifest? {
        lastLoadError = null
        val manifest = loadActiveFromDisk() ?: loadPreviousFromDisk() ?: loadBundled()
        if (manifest == null && lastLoadError == null) {
            recordLoadFailure("manifest", "no valid manifest was found", null)
        } else if (manifest != null) {
            lastLoadError = null
        }
        return manifest
    }

    private fun Manifest.withCustomRoutes(): Manifest {
        val validCustomRoutes =
            buildList {
                for (route in customServiceRepository.routes()) {
                    val candidate = copy(routes = routes + route)
                    if (ManifestValidator.validate(candidate).isEmpty()) add(route)
                }
            }
        return if (validCustomRoutes.isEmpty()) this else copy(routes = routes + validCustomRoutes)
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

    private fun decodeRaw(raw: String): Manifest? = runCatching { ManifestJson.decode(raw) }.getOrNull()

    private fun recordLoadFailure(
        source: String,
        reason: String,
        error: Throwable?,
    ) {
        val detail = error?.message?.takeIf(String::isNotBlank) ?: error?.javaClass?.simpleName
        lastLoadError = "$source: $reason${detail?.let { " ($it)" } ?: ""}"
        Log.e(TAG, "Manifest load failed: $lastLoadError", error)
    }

    private fun manifestDir(): File = File(context.filesDir, "manifest").apply { mkdirs() }

    private fun activeFile(): File = File(manifestDir(), "active.json")

    private fun previousFile(): File = File(manifestDir(), "previous.json")

    private companion object {
        const val TAG = "ManifestRepository"
        const val BUNDLED_ASSET_NAME = "routes.json"
    }
}
