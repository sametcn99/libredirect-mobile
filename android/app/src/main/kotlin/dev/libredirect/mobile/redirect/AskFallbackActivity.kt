package dev.libredirect.mobile.redirect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserLauncher
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.ui.theme.LibRedirectMobileTheme

internal class AskFallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null || UrlParser.parse(url)?.scheme !in setOf("http", "https")) {
            finish()
            return
        }
        setContent {
            LibRedirectMobileTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.inspect_no_instance)) },
                    text = { Text(stringResource(R.string.original_fallback_ask_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            val launched = BrowserLauncher(this).launch(this, url, intent.getStringExtra(EXTRA_TARGET))
                            if (!launched) Toast.makeText(this, R.string.redirect_no_browser, Toast.LENGTH_SHORT).show()
                            finish()
                        }) { Text(stringResource(R.string.inspect_unmodified)) }
                    },
                    dismissButton = {
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TARGET = "target"

        fun start(
            context: Context,
            url: String,
            target: String?,
        ) {
            context.startActivity(
                Intent(context, AskFallbackActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TARGET, target),
            )
        }
    }
}
