@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.browser.BrowserInfo
import dev.libredirect.mobile.browser.BrowserLauncher

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
                title = { Text("Varsayılan Tarayıcı") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
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
                    headlineContent = { Text("Sistem Varsayılanı") },
                    supportingContent = { Text("Cihazınızın sistem düzeyindeki varsayılan tarayıcısını kullanır") },
                    leadingContent = {
                        RadioButton(selected = selectedPackageName == null, onClick = { onBrowserSelected(null) })
                    },
                    modifier = Modifier.clickable { onBrowserSelected(null) },
                )
                HorizontalDivider()
            }

            items(browsers, key = BrowserInfo::packageName) { browser ->
                val isInApp = browser.packageName == BrowserLauncher.IN_APP_BROWSER_PACKAGE
                ListItem(
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(browser.label)
                            if (isInApp) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = "Önerilen",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    },
                    supportingContent = {
                        if (isInApp) {
                            Text("Açılan bağlantılar LibRedirect'in çok sekmeli dahili tarayıcısında açılır")
                        } else {
                            Text(browser.packageName)
                        }
                    },
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
