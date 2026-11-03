package dev.libredirect.mobile.manifest

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.libredirect.mobile.LibRedirectApplication
import java.util.concurrent.TimeUnit

/**
 * Optional daily background refresh of the frontend instance lists (opt-in
 * setting, off by default). Reuses the exact manual-refresh pipeline —
 * fetch from upstream, merge, validate, self-test, atomically activate —
 * so a background run can never activate data the manual path wouldn't.
 * Any failure keeps the last known good manifest, same as a rejected
 * manual refresh.
 */
class InstanceRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val manifestRepository
        get() = (applicationContext as LibRedirectApplication).manifestRepository

    override suspend fun doWork(): Result =
        when (val result = manifestRepository.refresh(ManifestEndpoints.INSTANCES_DATA_URL)) {
            is RefreshResult.Activated, is RefreshResult.NotModified -> Result.success()
            // Network trouble is worth a backoff retry; data problems are not.
            is RefreshResult.Rejected ->
                if (result.reason == RefreshRejectionReason.FETCH_FAILED) {
                    Result.retry()
                } else {
                    Result.success()
                }
        }

    companion object {
        const val WORK_NAME = "instance-refresh"

        /** Daily over any working network connection (not Wi-Fi-only, not charging-only). */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<InstanceRefreshWorker>(1, TimeUnit.DAYS)
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
