package dev.libredirect.mobile.update

import dev.libredirect.mobile.core.version.AppVersion
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App version checking against GitHub Releases: a manual "check now" path and
 * a throttled automatic path (see [UpdateThrottle.AUTO_CHECK_INTERVAL_MS]).
 * The newest known release is cached in settings so the update banner
 * survives restarts. Failed checks leave the cache untouched and normally do
 * not advance the throttle so the next launch retries — but after repeated
 * consecutive failures the throttle advances once, so an unreachable GitHub
 * does not cost a request on every single launch.
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
        UpdateThrottle.shouldAutoCheck(
            lastCheckAt = settingsRepository.updateLastCheckAt(),
            nowMillis = System.currentTimeMillis(),
        )

    @Suppress("ReturnCount")
    suspend fun checkNow(currentVersionName: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val latest = checker.latestRelease()
            if (latest == null) {
                val failures = settingsRepository.updateFailedChecksSinceSuccess() + 1
                settingsRepository.setUpdateFailedChecksSinceSuccess(failures)
                UpdateThrottle
                    .backoffTimestamp(failures, System.currentTimeMillis())
                    ?.let { settingsRepository.setUpdateLastCheckAt(it) }
                return@withContext UpdateCheckResult.Failed
            }

            // Only successful checks refresh the throttle timestamp, and they
            // reset the consecutive-failure counter.
            settingsRepository.setUpdateFailedChecksSinceSuccess(0)
            settingsRepository.setUpdateLastCheckAt(System.currentTimeMillis())
            if (AppVersion.isNewer(latest.versionName, currentVersionName)) {
                settingsRepository.setCachedUpdate(latest.versionName, latest.releaseUrl)
                UpdateCheckResult.Available(latest)
            } else {
                settingsRepository.setCachedUpdate(version = "", releaseUrl = "")
                UpdateCheckResult.UpToDate
            }
        }
}
