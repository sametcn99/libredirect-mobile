package dev.libredirect.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.browser.ShortcutActions
import dev.libredirect.mobile.ui.AppNavHost
import dev.libredirect.mobile.ui.MainViewModel
import dev.libredirect.mobile.ui.ROUTE_SERVICES
import dev.libredirect.mobile.ui.browser.BrowserViewModel
import dev.libredirect.mobile.ui.theme.LibRedirectMobileTheme

class MainActivity : AppCompatActivity() {
    private val browserViewModel: BrowserViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On a cold start the browser already begins with one fresh tab, so
        // the "new tab" shortcut only needs to add one when resuming.
        handleIntent(intent, isColdStart = savedInstanceState == null)
        setContent {
            LibRedirectMobileTheme {
                AppNavHost(browserViewModel = browserViewModel, mainViewModel = mainViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, isColdStart = false)
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

            BrowserLauncher.ACTION_OPEN_IN_APP_BROWSER, Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return
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
