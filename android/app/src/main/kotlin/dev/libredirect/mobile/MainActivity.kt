package dev.libredirect.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.ui.AppNavHost
import dev.libredirect.mobile.ui.browser.BrowserViewModel
import dev.libredirect.mobile.ui.theme.LibRedirectMobileTheme

class MainActivity : ComponentActivity() {
    private val browserViewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            LibRedirectMobileTheme {
                AppNavHost(browserViewModel = browserViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (intent.action == BrowserLauncher.ACTION_OPEN_IN_APP_BROWSER ||
            intent.action == Intent.ACTION_VIEW
        ) {
            browserViewModel.openUrlInNewOrActiveTab(url)
        }
    }
}
