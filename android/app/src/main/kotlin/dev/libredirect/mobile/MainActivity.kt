package dev.libredirect.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.libredirect.mobile.ui.AppNavHost
import dev.libredirect.mobile.ui.theme.LibRedirectMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibRedirectMobileTheme {
                AppNavHost()
            }
        }
    }
}
