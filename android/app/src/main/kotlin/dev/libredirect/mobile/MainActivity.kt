package dev.libredirect.mobile

import android.app.PictureInPictureParams
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.browser.ShortcutActions
import dev.libredirect.mobile.media.MediaPlaybackService
import dev.libredirect.mobile.ui.AppNavHost
import dev.libredirect.mobile.ui.MainViewModel
import dev.libredirect.mobile.ui.ROUTE_SERVICES
import dev.libredirect.mobile.ui.browser.BrowserViewModel
import dev.libredirect.mobile.ui.lock.AppLockGate
import dev.libredirect.mobile.ui.settings.BrowserRoleHost
import dev.libredirect.mobile.ui.theme.LibRedirectMobileTheme

class MainActivity : AppCompatActivity(), BrowserRoleHost {
    private companion object {
        const val ASPECT_SCALE = 1000
        const val MIN_PIP_ASPECT = 0.4f
        const val MAX_PIP_ASPECT = 2.4f
        const val WIDE_ASPECT = 16
    }

    private val browserViewModel: BrowserViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()

    private val roleRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    /** Runtime notification permission for the media notification (4.6, API 33+). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On a cold start the browser already begins with one fresh tab, so
        // the "new tab" shortcut only needs to add one when resuming. A mere
        // recreation (rotation, theme change) re-delivers the creating intent,
        // but it was handled on the first creation — running it again would
        // resurrect closed tabs and switch tabs, so only a true cold start
        // processes it here.
        if (savedInstanceState == null) {
            handleIntent(intent, isColdStart = true)
        }
        setContent {
            LibRedirectMobileTheme {
                val isAppLocked by mainViewModel.isAppLocked.collectAsState()
                AppLockGate(
                    isLocked = isAppLocked,
                    onUnlocked = mainViewModel::unlockApp,
                ) {
                    AppNavHost(browserViewModel = browserViewModel, mainViewModel = mainViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, isColdStart = false)
    }

    override fun onStop() {
        super.onStop()
        // Re-arm the app lock once the activity is fully hidden (not on
        // transient overlays like share sheets); the gate itself no-ops when
        // the feature is off.
        mainViewModel.lockApp()
        // 4.5: opt-in wipe of incognito tabs when the app leaves the foreground.
        mainViewModel.maybeCloseIncognitoTabs(browserViewModel)
        // 4.6: keep audio alive behind a foreground service while opted in
        // and the active tab's page is actually playing media.
        mainViewModel.maybeStartMediaPlaybackService()
    }

    override fun onDestroy() {
        if (isFinishing) {
            MediaPlaybackService.stop(this)
        }
        super.onDestroy()
    }

    // --- 4.1 picture-in-picture ---

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Entering PiP when the user leaves while fullscreen video plays.
        if (browserViewModel.shouldEnterPictureInPicture()) {
            tryEnterPip()
        }
    }

    private fun tryEnterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val aspect = browserViewModel.fullscreenAspectRatio()
        val rational =
            when {
                aspect != null && aspect in MIN_PIP_ASPECT..MAX_PIP_ASPECT ->
                    Rational((aspect * ASPECT_SCALE).toInt(), ASPECT_SCALE.toInt())
                else -> Rational(WIDE_ASPECT, 1)
            }
        val params = PictureInPictureParams.Builder().setAspectRatio(rational).build()
        try {
            enterPictureInPictureMode(params)
        } catch (_: IllegalStateException) {
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPipMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        browserViewModel.onPictureInPictureModeChanged(isInPipMode)
    }

    override fun requestBrowserRole() {
        // RoleManager exists from API 29; below that the VIEW intent filter
        // still makes the app selectable as a link handler.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roleManager = getSystemService(RoleManager::class.java)
        val canRequest =
            roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
        if (canRequest) {
            roleRequestLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
        }
    }

    /** Runtime POST_NOTIFICATIONS request for the media notification (API 33+). */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = PackageManager.PERMISSION_GRANTED
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == granted) return
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleIntent(
        intent: Intent?,
        isColdStart: Boolean,
    ) {
        when (intent?.action) {
            ShortcutActions.OPEN_NEW_TAB -> {
                if (!isColdStart) {
                    browserViewModel.openNewTab()
                }
            }

            ShortcutActions.SHOW_SERVICES -> mainViewModel.onShortcutRoute(ROUTE_SERVICES)

            ShortcutActions.OPEN_SERVICE ->
                browserViewModel.openServiceShortcut(intent.getStringExtra(ShortcutActions.EXTRA_ROUTE_ID))

            // Only the app's own in-app-browser action is honored. This
            // activity is exported (it is the launcher), so honoring raw
            // ACTION_VIEW here would let any app bypass RedirectActivity and
            // its routing settings — external links must arrive through
            // RedirectActivity's intent filters instead.
            BrowserLauncher.ACTION_OPEN_IN_APP_BROWSER -> {
                val url = intent.dataString ?: return
                // Exported surface: validate the scheme before the URL is
                // used, so a crafted intent cannot smuggle in a non-web
                // scheme (intent:, file:, javascript:, ...).
                if (!url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true)
                ) {
                    return
                }
                browserViewModel.openExternalLinkInNewTab(
                    url = url,
                    originalUrl = intent.getStringExtra(BrowserLauncher.EXTRA_ORIGINAL_URL),
                    routeId = intent.getStringExtra(BrowserLauncher.EXTRA_ROUTE_ID),
                    frontendId = intent.getStringExtra(BrowserLauncher.EXTRA_FRONTEND_ID),
                    instance = intent.getStringExtra(BrowserLauncher.EXTRA_INSTANCE),
                )
            }
        }
    }
}
