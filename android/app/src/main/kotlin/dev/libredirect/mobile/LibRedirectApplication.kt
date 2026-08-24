package dev.libredirect.mobile

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import dev.libredirect.mobile.manifest.ManifestRepository
import kotlin.system.exitProcess

class LibRedirectApplication : Application() {
    val manifestRepository: ManifestRepository by lazy { ManifestRepository(this) }

    override fun onCreate() {
        super.onCreate()
        installCrashRecovery()
    }

    /**
     * Last-resort safety net for exceptions that slip past every other
     * boundary (coroutine try/catch, Compose state validation, etc.): log it,
     * then restart into a clean [MainActivity] instead of leaving the OS to
     * show its bare "app has stopped" dialog and kill the process mid-state.
     */
    private fun installCrashRecovery() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Unhandled exception on ${thread.name}", throwable)
            val restarted =
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        },
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            if (!restarted) defaultHandler?.uncaughtException(thread, throwable)
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    private companion object {
        const val TAG = "LibRedirectApplication"
    }
}
