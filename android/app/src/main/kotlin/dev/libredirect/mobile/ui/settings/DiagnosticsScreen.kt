@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R

/**
 * Local diagnostics (§6): version, routing-data revision, rule counts,
 * WebView version, API level, a URL-free settings summary and the in-memory
 * recent-error ring — copyable, never transmitted anywhere.
 */
@Composable
fun DiagnosticsScreen(
    diagnosticsText: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = diagnosticsText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            Button(
                onClick = { context.copyDiagnostics(diagnosticsText) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostics_copy))
            }
        }
    }
}

private fun Context.copyDiagnostics(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", text))
    Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
}
