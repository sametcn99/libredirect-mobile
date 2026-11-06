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
import dev.libredirect.mobile.browser.InstanceProbe
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.settings.SettingsRepository
import dev.libredirect.mobile.ui.browser.BrowserDataRepository
import java.util.concurrent.TimeUnit

/**
 * Optional daily background latency measurement (2.3, opt-in, off by
 * default). Measures only the *selected* frontend of every enabled route
 * that needs instances, capped per frontend, with the same bounded probe
 * the UI uses — results feed the latency-weighted instance selection and
 * the service health panel.
 */
class InstanceHealthWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LibRedirectApplication
        val settings = SettingsRepository(app).current()
        val targets =
            if (settings.backgroundLatencyMeasureEnabled) {
                measureTargets(app, settings)
            } else {
                emptyMap()
            }
        if (targets.isEmpty()) return Result.success()

        val probe = InstanceProbe()
        val dataRepository = BrowserDataRepository(app)
        targets.values.forEach { instances ->
            dataRepository.saveLatencies(probe.measure(instances))
        }
        return Result.success()
    }

    /** Selected frontend of every enabled route that needs instances, capped. */
    private suspend fun measureTargets(
        app: LibRedirectApplication,
        settings: AppSettings,
    ): Map<String, List<String>> {
        val manifest = app.manifestRepository.activeManifest() ?: return emptyMap()
        val frontends =
            manifest.routes
                .filter { settings.isRouteEnabled(it.id) }
                .mapNotNull { route ->
                    val frontendId = settings.selectedFrontends[route.id] ?: route.frontends.first().id
                    route.frontends.find { it.id == frontendId }?.takeIf { it.instances.isNotEmpty() }
                }
        return frontends.associate { it.id to it.instances.take(MAX_INSTANCES_PER_FRONTEND) }
    }

    companion object {
        const val WORK_NAME = "instance-health-measure"
        const val MAX_INSTANCES_PER_FRONTEND = 16

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<InstanceHealthWorker>(1, TimeUnit.DAYS)
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
