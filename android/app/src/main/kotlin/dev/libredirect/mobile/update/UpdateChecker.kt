package dev.libredirect.mobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
)

/**
 * Checks GitHub's "latest release" endpoint for a newer signed build than the
 * one currently installed (see .github/workflows/android-release.yml, which
 * tags releases "v{versionName}" to match). Purely informational and
 * best-effort: any failure - offline, GitHub down, no releases yet, unexpected
 * response shape - returns null rather than surfacing an error, matching the
 * project's fail-safe philosophy elsewhere (ManifestRepository, RemoteManifestFetcher).
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val owner: String = "sametcn99",
    private val repo: String = "libredirect-mobile",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latestRelease(): UpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                fetch()
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private fun fetch(): UpdateInfo? {
        val request =
            Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        return client.newCall(request).execute().use { response ->
            parseResponse(response)
        }
    }

    private fun parseResponse(response: okhttp3.Response): UpdateInfo? =
        if (!response.isSuccessful) {
            null
        } else {
            response.body?.string()?.take(MAX_RESPONSE_CHARS)?.let(::decodeRelease)
        }

    private fun decodeRelease(body: String): UpdateInfo? =
        json.decodeFromString(GitHubRelease.serializer(), body).takeUnless {
            it.draft || it.prerelease
        }?.let { release ->
            UpdateInfo(
                versionName = release.tagName.removePrefix("v").removePrefix("V"),
                releaseUrl = release.htmlUrl,
            )
        }

    private companion object {
        const val MAX_RESPONSE_CHARS = 200_000
        const val DEFAULT_TIMEOUT_SECONDS = 8L

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
