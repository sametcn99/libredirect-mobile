package dev.libredirect.mobile.manifest

import android.content.Context
import android.util.Log
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.ManifestJson
import dev.libredirect.mobile.core.manifest.ManifestValidator
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.RoutingContext
import dev.libredirect.mobile.core.routing.UrlRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Manifest priority (project plan §11/§12): Last Known Good (on-disk
 * `active.json`, then `previous.json`) over the bundled asset — the file
 * chain itself lives in [ManifestFileStore]. A refresh fetches the upstream
 * instance data, decodes it, merges the per-frontend instance lists into
 * the active manifest, validates, and self-tests — and the result is only
 * written to `active.json` if every one of those steps passes. Any failure
 * leaves the existing active manifest (or the bundled asset, if nothing has
 * ever been activated) untouched and in use.
 */
class ManifestRepository(
    context: Context,
    private val fetcher: RemoteManifestFetcher = RemoteManifestFetcher(),
    private val customServiceRepository: CustomServiceRepository = CustomServiceRepository(context),
) {
    private val store =
        ManifestFileStore(
            manifestDir = File(context.filesDir, "manifest"),
            bundledAssetText = {
                context.assets.open(BUNDLED_ASSET_NAME).bufferedReader().use { it.readText() }
            },
            onFailure = { diagnostic, error -> Log.e(TAG, "Manifest load failed: $diagnostic", error) },
        )

    @Volatile
    private var cached: Manifest? = null

    private val _activeManifestUpdates = MutableStateFlow<Manifest?>(null)

    /**
     * The composite active manifest (custom routes included), re-emitted on
     * every change: initial load, instance refresh, custom route add/remove.
     * Long-lived consumers — the in-app browser resolves links continuously —
     * follow this instead of caching a snapshot that goes stale after the
     * first read.
     */
    val activeManifestUpdates: StateFlow<Manifest?> = _activeManifestUpdates.asStateFlow()

    /** Never null in practice — the bundled asset is a build-time guarantee — but
     * callers must still treat a null result as "route nothing, pass everything through"
     * (fail-open) rather than crash, in case that guarantee is ever violated. */
    fun activeManifest(): Manifest? {
        _activeManifestUpdates.value?.let { return it }
        val manifest = store.loadBase()
        cached = manifest
        return manifest?.withCustomRoutes().also { _activeManifestUpdates.value = it }
    }

    /**
     * Returns a diagnostic for the last failed load, if no valid manifest was
     * available. The detail is intentionally kept here so the UI can explain
     * an empty service list instead of silently treating it as a valid state.
     */
    fun lastLoadError(): String? = store.lastDiagnostic

    fun customRoutes(): List<Route> = customServiceRepository.routes()

    /** Adds a custom route only when it remains valid and does not shadow a built-in host/id. */
    fun addCustomRoute(route: Route): Result<Unit> {
        val base = store.loadBase()
        return if (base == null) {
            Result.failure(IllegalStateException("No routing manifest available"))
        } else {
            val existing = base.routes + customServiceRepository.routes().filterNot { it.id == route.id }
            val errors = ManifestValidator.validate(base.copy(routes = existing + route))
            if (errors.isNotEmpty()) {
                Result.failure(IllegalArgumentException(errors.joinToString("; ")))
            } else {
                customServiceRepository.save(route)
                publishActiveManifest()
                Result.success(Unit)
            }
        }
    }

    fun removeCustomRoute(routeId: String) {
        customServiceRepository.delete(routeId)
        publishActiveManifest()
    }

    /**
     * Fetches the upstream instance data and merges its per-frontend HTTPS
     * clearnet instance lists into the base manifest. Routes, hosts and
     * frontend structure are frozen at build time; only instances refresh.
     */
    suspend fun refresh(instancesUrl: String): RefreshResult {
        val raw = fetcher.fetchBytes(instancesUrl) ?: return RefreshResult.Rejected(RefreshRejectionReason.FETCH_FAILED)
        return refreshParsed(InstanceDataParser.parse(raw))
    }

    private fun refreshParsed(instanceData: Map<String, List<String>>?): RefreshResult =
        if (instanceData.isNullOrEmpty()) {
            RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)
        } else {
            refreshBase(instanceData)
        }

    private fun refreshBase(instanceData: Map<String, List<String>>): RefreshResult =
        store.loadBase()?.let { base ->
            when (val candidate = InstanceMerger.merge(base, instanceData)) {
                null -> RefreshResult.NotModified
                else -> refreshCandidate(candidate)
            }
        } ?: RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)

    private fun refreshCandidate(candidate: Manifest): RefreshResult =
        when {
            candidate.schemaVersion != ManifestValidator.SUPPORTED_SCHEMA_VERSION ->
                RefreshResult.Rejected(RefreshRejectionReason.UNSUPPORTED_SCHEMA_VERSION)
            ManifestValidator.validate(candidate).isNotEmpty() ->
                RefreshResult.Rejected(RefreshRejectionReason.MALFORMED_MANIFEST)
            !selfTest(candidate) -> RefreshResult.Rejected(RefreshRejectionReason.SELF_TEST_FAILED)
            else -> activateCandidate(candidate)
        }

    private fun activateCandidate(candidate: Manifest): RefreshResult =
        try {
            store.activateAtomically(ManifestJson.encode(candidate).toByteArray(Charsets.UTF_8))
            cached = candidate
            _activeManifestUpdates.value = candidate.withCustomRoutes()
            RefreshResult.Activated(candidate.revision)
        } catch (error: IOException) {
            Log.e(TAG, "Manifest activation failed (storage error)", error)
            RefreshResult.Rejected(RefreshRejectionReason.STORAGE_ERROR)
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

    /** Re-publishes the composite manifest after a change to the custom routes. */
    private fun publishActiveManifest() {
        val base = cached ?: store.loadBase()?.also { cached = it }
        _activeManifestUpdates.value = base?.withCustomRoutes()
    }

    private companion object {
        const val TAG = "ManifestRepository"
        const val BUNDLED_ASSET_NAME = "routes.json"
    }
}
