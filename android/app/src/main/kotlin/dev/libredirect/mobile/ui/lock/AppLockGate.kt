package dev.libredirect.mobile.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.libredirect.mobile.R

/**
 * Gate over the whole app while the app lock (C4) is engaged. It renders as
 * an opaque overlay ON TOP of the content instead of replacing it: the
 * content (and its navigation) must stay alive and reactive while locked,
 * so a link that arrives from another app can still switch to the browser
 * tab behind the gate and be visible the moment the user unlocks.
 * MainActivity re-locks on every backgrounding, so this is shown again each
 * time the user returns.
 */
@Composable
fun AppLockGate(
    isLocked: Boolean,
    onUnlocked: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (isLocked) {
            LockOverlay(onUnlocked = onUnlocked)
        }
    }
}

@Composable
private fun LockOverlay(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    var attempt by remember { mutableIntStateOf(0) }
    val onUnlockedState = rememberUpdatedState(onUnlocked)

    LaunchedEffect(attempt) {
        activity?.let { target -> authenticate(target) { onUnlockedState.value() } }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_lock_locked_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { attempt += 1 },
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.app_lock_unlock))
        }
        if (activity == null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_lock_host_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun authenticate(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
) {
    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
            },
        )
    val title = activity.getString(R.string.app_lock_unlock_title)
    val combined =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(title)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ).build()
    try {
        prompt.authenticate(combined)
    } catch (_: IllegalArgumentException) {
        // A few API levels reject the combined authenticator set outright;
        // fall back to biometrics-only so enrolled devices can still unlock.
        val biometricOnly =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        try {
            prompt.authenticate(biometricOnly)
        } catch (_: IllegalArgumentException) {
            // No usable authenticator at all (the screen lock was removed
            // after the feature was enabled): the gate stays up with its
            // Unlock button rather than crashing.
        }
    }
}
