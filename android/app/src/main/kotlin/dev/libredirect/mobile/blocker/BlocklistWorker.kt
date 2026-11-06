package dev.libredirect.mobile.blocker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.libredirect.mobile.LibRedirectApplication
import dev.libredirect.mobile.settings.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Daily refresh of the content-blocker list (4.2), gated by the same
 * opt-in-ish setting as blocking itself: the worker runs only while content
 * blocking is enabled. Fetch, validate and activate follow the manifest
 * pipeline's shape — a failed download keeps the previously active list.
 */
class BlocklistWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LibRedirectApplication
        val settings = SettingsRepository(app).current()
        val raw =
            if (!settings.contentBlockerEnabled) {
                null
            } else {
                BlocklistFetcher().fetch()
            }
        val activated = raw != null && BlocklistStore(app).activate(raw)
        return when {
            raw == null && settings.contentBlockerEnabled -> Result.retry()
            else -> Result.success()
        }.let { result ->
            if (!activated && raw != null) Result.retry() else result
        }
    }

    companion object {
        const val WORK_NAME = "blocklist-refresh"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<BlocklistWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
