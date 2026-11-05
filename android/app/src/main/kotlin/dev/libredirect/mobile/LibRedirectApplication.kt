package dev.libredirect.mobile

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import dev.libredirect.mobile.manifest.ManifestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.system.exitProcess

class LibRedirectApplication : Application() {
    val manifestRepository: ManifestRepository by lazy { ManifestRepository(this) }

    /**
     * Application-lifetime scope for work that must outlive any single
     * activity: [RedirectActivity][dev.libredirect.mobile.redirect.RedirectActivity]
     * is a NoDisplay screen the user can leave at any moment — a slow first
     * start makes that likely — and the link it is routing must still reach
     * the browser afterwards instead of dying with the cancelled activity
     * scope.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        installCrashRecovery()
    }

    /**
     * Last-resort safety net for exceptions that slip past every other
     * boundary (coroutine try/catch, Compose state validation, etc.): log it,
     * then restart into a clean [MainActivity] instead of leaving the OS to
     * show its bare "app has stopped" dialog and kill the process mid-state.
     *
     * At most one restart happens per [CRASH_RESTART_WINDOW_MILLIS]: a crash
     * that recurs immediately after a restart (deterministic failure in
     * Application/Activity init) is handed back to the default handler
     * instead of looping forever. The marker is persisted with synchronous
     * SharedPreferences because a crash handler cannot await DataStore and
     * the flag must survive the process being killed.
     */
    private fun installCrashRecovery() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Unhandled exception on ${thread.name}", throwable)
            val crashPrefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            val lastRestart = crashPrefs.getLong(KEY_LAST_CRASH_RESTART, 0L)
            val recentlyRestarted = System.currentTimeMillis() - lastRestart < CRASH_RESTART_WINDOW_MILLIS
            val restarted =
                if (recentlyRestarted) {
                    false
                } else {
                    try {
                        crashPrefs
                            .edit()
                            .putLong(KEY_LAST_CRASH_RESTART, System.currentTimeMillis())
                            .commit()
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            },
                        )
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            if (!restarted) defaultHandler?.uncaughtException(thread, throwable)
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    private companion object {
        const val TAG = "LibRedirectApplication"
        const val CRASH_PREFS = "crash_recovery"
        const val KEY_LAST_CRASH_RESTART = "last_crash_restart_at"
        const val CRASH_RESTART_WINDOW_MILLIS = 30_000L
    }
}
