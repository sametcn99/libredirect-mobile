package dev.libredirect.mobile

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.browser.ShortcutActions
import dev.libredirect.mobile.ui.AppNavHost
import dev.libredirect.mobile.ui.MainViewModel
import dev.libredirect.mobile.ui.ROUTE_SERVICES
import dev.libredirect.mobile.ui.browser.BrowserViewModel
import dev.libredirect.mobile.ui.lock.AppLockGate
import dev.libredirect.mobile.ui.settings.BrowserRoleHost
import dev.libredirect.mobile.ui.theme.LibRedirectMobileTheme

class MainActivity : AppCompatActivity(), BrowserRoleHost {
    private val browserViewModel: BrowserViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()

    private val roleRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

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
