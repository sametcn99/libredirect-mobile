@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.browser.BrowserInfo
import dev.libredirect.mobile.browser.BrowserLauncher

@Composable
fun BrowserPickerScreen(
    browsers: List<BrowserInfo>,
    selectedPackageName: String?,
    onBack: () -> Unit,
    onBrowserSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSystemDefault = selectedPackageName == BrowserLauncher.SYSTEM_DEFAULT_PACKAGE

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_picker_title)) },
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
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.browser_picker_system_default)) },
                    supportingContent = { Text(stringResource(R.string.browser_picker_system_default_desc)) },
                    leadingContent = {
                        RadioButton(
                            selected = isSystemDefault,
                            onClick = { onBrowserSelected(BrowserLauncher.SYSTEM_DEFAULT_PACKAGE) },
                        )
                    },
                    modifier = Modifier.clickable { onBrowserSelected(BrowserLauncher.SYSTEM_DEFAULT_PACKAGE) },
                )
                HorizontalDivider()
            }

            items(browsers, key = BrowserInfo::packageName) { browser ->
                val isInApp = browser.packageName == BrowserLauncher.IN_APP_BROWSER_PACKAGE
                val isSelected = !isSystemDefault && browser.packageName == selectedPackageName
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
                                        text = stringResource(R.string.browser_picker_recommended),
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
                            Text(stringResource(R.string.browser_picker_in_app_desc))
                        } else {
                            Text(browser.packageName)
                        }
                    },
                    leadingContent = {
                        RadioButton(
                            selected = isSelected,
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
