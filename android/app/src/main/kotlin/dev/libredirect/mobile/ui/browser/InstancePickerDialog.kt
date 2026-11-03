package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R

private const val LATENCY_FAILED_MILLIS = -1L

/**
 * Lets the user re-route the active tab's original link to another instance of
 * its frontend: a random pick or a manually chosen one from the manifest.
 * Optional latency measurement ranks instances by time-to-first-byte and
 * highlights the fastest one so the user can pin it.
 */
@Composable
fun InstancePickerDialog(
    alternatives: InstanceAlternatives,
    latencies: Map<String, Long>,
    isMeasuring: Boolean,
    onMeasure: () -> Unit,
    onPickRandom: () -> Unit,
    onPickInstance: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fastestInstance = latencies.filterValues { it > LATENCY_FAILED_MILLIS }.minByOrNull { it.value }?.key
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.browser_instance_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.browser_instance_picker_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        stringResource(
                            R.string.browser_redirect_from_to_fmt,
                            alternatives.routeName,
                            alternatives.frontendName,
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                RandomInstanceRow(onSelect = onPickRandom)
                MeasureInstancesRow(isMeasuring = isMeasuring, onSelect = onMeasure)

                orderedInstances(alternatives.instances, latencies).forEachIndexed { index, instance ->
                    val originalIndex = alternatives.instances.indexOf(instance)
                    InstanceRow(
                        instance = instance,
                        isCurrent = originalIndex == alternatives.currentIndex,
                        isFastest = instance == fastestInstance && alternatives.instances.size > 1,
                        latencyMillis = latencies[instance],
                        onSelect = { onPickInstance(instance) },
                        showTopDivider = index == 0,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

/**
 * Measured instances lead the list, fastest first; unmeasured ones keep
 * their manifest order after them.
 */
private fun orderedInstances(
    instances: List<String>,
    latencies: Map<String, Long>,
): List<String> =
    if (latencies.isEmpty()) {
        instances
    } else {
        instances.sortedWith(
            compareBy(
                { instance -> instance !in latencies },
                { instance -> latencies[instance] ?: Long.MAX_VALUE },
            ),
        )
    }

@Composable
private fun RandomInstanceRow(onSelect: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Shuffle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.browser_instance_random),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MeasureInstancesRow(
    isMeasuring: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.browser_instance_measure), onClick = onSelect)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isMeasuring) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Default.NetworkCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text =
                stringResource(
                    if (isMeasuring) R.string.browser_instance_measuring else R.string.browser_instance_measure,
                ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun InstanceRow(
    instance: String,
    isCurrent: Boolean,
    isFastest: Boolean,
    latencyMillis: Long?,
    onSelect: () -> Unit,
    showTopDivider: Boolean,
) {
    if (showTopDivider) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = isCurrent, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = BrowserTab.hostOf(instance),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = instance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            isFastest -> {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = stringResource(R.string.browser_instance_fastest),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            latencyMillis != null -> {
                Text(
                    text =
                        if (latencyMillis > LATENCY_FAILED_MILLIS) {
                            stringResource(R.string.browser_instance_latency_fmt, latencyMillis)
                        } else {
                            stringResource(R.string.browser_instance_latency_failed)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isCurrent) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.browser_instance_current),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
