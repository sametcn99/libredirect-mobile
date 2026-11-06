package dev.libredirect.mobile.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R

/**
 * First-launch guide (5.2): what the app does, default-browser role, the
 * Android 12+ "open by default" fix for installed service apps, and the
 * share/text-selection entry points. Reopenable from Settings.
 */
private const val FIRST_STEP = 1
private const val TOTAL_STEPS = 4
private const val STEP_WELCOME = 1
private const val STEP_BROWSER = 2
private const val STEP_APP_LINKS = 3

@Composable
fun OnboardingScreen(
    installedServiceApps: List<Pair<String, String>>,
    onRequestBrowserRole: () -> Unit,
    onOpenAppByDefault: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(FIRST_STEP) }
    val totalSteps = TOTAL_STEPS

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_step_fmt, step),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val (icon, titleRes, descRes) =
                    when (step) {
                        STEP_WELCOME ->
                            Triple(
                                Icons.Default.Security,
                                R.string.onboarding_welcome_title,
                                R.string.onboarding_welcome_desc,
                            )
                        STEP_BROWSER ->
                            Triple(
                                Icons.Default.Public,
                                R.string.onboarding_default_browser_title,
                                R.string.onboarding_default_browser_desc,
                            )
                        STEP_APP_LINKS ->
                            Triple(
                                Icons.Default.Lock,
                                R.string.onboarding_app_links_title,
                                R.string.onboarding_app_links_desc,
                            )
                        else ->
                            Triple(
                                Icons.Default.Share,
                                R.string.onboarding_share_title,
                                R.string.onboarding_share_desc,
                            )
                    }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(descRes), style = MaterialTheme.typography.bodyMedium)
            }
        }

        when (step) {
            STEP_BROWSER -> {
                Button(onClick = onRequestBrowserRole, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_default_browser_btn))
                }
            }
            STEP_APP_LINKS -> {
                if (installedServiceApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.onboarding_app_links_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    installedServiceApps.forEach { (packageName, label) ->
                        TextButton(
                            onClick = { onOpenAppByDefault(packageName) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.onboarding_app_links_btn_fmt, label))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.onboarding_skip))
            }
            Button(
                onClick = {
                    if (step >= totalSteps) {
                        onDone()
                    } else {
                        step += 1
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (step >= totalSteps) R.string.onboarding_get_started else R.string.onboarding_next,
                    ),
                )
            }
        }
    }
}
