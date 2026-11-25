package dev.libredirect.mobile.media

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.libredirect.mobile.MainActivity
import dev.libredirect.mobile.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class BackgroundAudioLifecycleTest {
    @Test
    fun optedInPlaybackStartsForegroundServiceWhileActivityIsResumed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        val previousSetting = runBlocking { settings.settings.first().backgroundAudioEnabled }
        MediaPlaybackBus.reset()
        try {
            runBlocking { settings.setBackgroundAudioEnabled(true) }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { MediaPlaybackBus.reportPlaying(true) }
                val activityManager = requireNotNull(context.getSystemService(ActivityManager::class.java))
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!hasForegroundPlaybackService(activityManager) && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(50)
                }

                // Android 17 grants while-in-use capability when the service starts here.
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertTrue("Playback service must be foreground before the activity stops", hasForegroundPlaybackService(activityManager))
            }
        } finally {
            MediaPlaybackService.stop(context)
            MediaPlaybackBus.reset()
            runBlocking { settings.setBackgroundAudioEnabled(previousSetting) }
        }
    }

    @Suppress("DEPRECATION")
    private fun hasForegroundPlaybackService(activityManager: ActivityManager): Boolean =
        activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
            service.service.className == MediaPlaybackService::class.java.name && service.foreground
        }
}
