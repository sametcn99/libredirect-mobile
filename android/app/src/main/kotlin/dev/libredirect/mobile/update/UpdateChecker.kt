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
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.take(MAX_RESPONSE_CHARS) ?: return null
            val release = json.decodeFromString(GitHubRelease.serializer(), body)
            if (release.draft || release.prerelease) return null
            return UpdateInfo(
                versionName = release.tagName.removePrefix("v").removePrefix("V"),
                releaseUrl = release.htmlUrl,
            )
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 200_000

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
    }
}
