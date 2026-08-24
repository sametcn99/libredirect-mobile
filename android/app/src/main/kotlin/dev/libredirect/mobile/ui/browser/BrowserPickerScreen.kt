@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.libredirect.mobile.browser.BrowserInfo

@Composable
fun BrowserPickerScreen(
    browsers: List<BrowserInfo>,
    selectedPackageName: String?,
    onBack: () -> Unit,
    onBrowserSelected: (String?) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Default browser") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxWidth(),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("System default") },
                    leadingContent = {
                        RadioButton(selected = selectedPackageName == null, onClick = { onBrowserSelected(null) })
                    },
                    modifier = Modifier.clickable { onBrowserSelected(null) },
                )
                HorizontalDivider()
            }

            items(browsers, key = BrowserInfo::packageName) { browser ->
                ListItem(
                    headlineContent = { Text(browser.label) },
                    leadingContent = {
                        RadioButton(
                            selected = browser.packageName == selectedPackageName,
                            onClick = { onBrowserSelected(browser.packageName) },
                        )
                    },
                    modifier = Modifier.clickable { onBrowserSelected(browser.packageName) },
                )
                HorizontalDivider()
            }
        }
    }
}
