package dev.libredirect.mobile.update

import dev.libredirect.mobile.core.version.AppVersion
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App version checking against GitHub Releases: a manual "check now" path and
 * a throttled automatic path (see [AUTO_CHECK_INTERVAL_MS]). The newest known
 * release is cached in settings so the update banner survives restarts, and a
 * failed check leaves both the cache and the last-check timestamp untouched so
 * the next launch retries.
 */
class UpdateRepository(
    private val settingsRepository: SettingsRepository,
    private val checker: UpdateChecker = UpdateChecker(),
) {
    /** Cached newer release, if it is still newer than the running build. */
    @Suppress("ReturnCount")
    suspend fun cachedUpdate(currentVersionName: String): UpdateInfo? {
        val cached = settingsRepository.cachedUpdate() ?: return null
        if (!AppVersion.isNewer(cached.versionName, currentVersionName)) return null
        return UpdateInfo(versionName = cached.versionName, releaseUrl = cached.releaseUrl)
    }

    suspend fun shouldAutoCheck(): Boolean =
        System.currentTimeMillis() - settingsRepository.updateLastCheckAt() > AUTO_CHECK_INTERVAL_MS

    suspend fun checkNow(currentVersionName: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val latest = checker.latestRelease() ?: return@withContext UpdateCheckResult.Failed

            // Only successful checks refresh the throttle timestamp: a failure
            // should not silence automatic checks for the next interval.
            settingsRepository.setUpdateLastCheckAt(System.currentTimeMillis())
            if (AppVersion.isNewer(latest.versionName, currentVersionName)) {
                settingsRepository.setCachedUpdate(latest.versionName, latest.releaseUrl)
                UpdateCheckResult.Available(latest)
            } else {
                settingsRepository.setCachedUpdate(version = "", releaseUrl = "")
                UpdateCheckResult.UpToDate
            }
        }

    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
