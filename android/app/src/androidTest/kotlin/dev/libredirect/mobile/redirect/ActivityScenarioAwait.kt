package dev.libredirect.mobile.redirect

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario

/**
 * Waits until the activity finishes itself. RedirectActivity is a NoDisplay
 * router that calls finish() as soon as the link handoff is done, so
 * `onActivity` races with the self-finish (observed failing with "Cannot run
 * onActivity since Activity has been destroyed already" on an API 28
 * emulator); polling the lifecycle state instead asserts the same thing —
 * the activity ran and completed — without the race.
 */
internal fun ActivityScenario<*>.awaitDestroyed() {
    val deadline = System.currentTimeMillis() + FINISH_TIMEOUT_MILLIS
    while (state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
}

private const val FINISH_TIMEOUT_MILLIS = 30_000L
private const val POLL_INTERVAL_MILLIS = 100L
