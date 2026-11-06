package dev.libredirect.mobile.ui.browser

/**
 * Pure state machine behind the browser's pull-to-refresh gesture (B6),
 * extracted so the threshold/arming rules are unit-testable. One instance
 * tracks a single drag; call [reset] when a new touch begins.
 *
 * [onDrag] accumulates only downward movement (`dy > 0`) while the WebView
 * sits at its top — the same gesture a page uses to overscroll — and reports
 * the trigger exactly once per drag once the threshold distance is reached.
 * A load starting mid-gesture cancels the accumulation for the rest of that
 * drag (the reload already in flight must not be re-triggered).
 */
class PullToRefreshAccumulator(private val thresholdPx: Float) {
    /** 0..1 visual progress of the pull indicator. */
    var progress: Float = 0f
        private set

    private var distance = 0f
    private var triggered = false

    fun reset() {
        distance = 0f
        triggered = false
        progress = 0f
    }

    /** @return true exactly once when the pull crosses [thresholdPx] (fire the reload). */
    fun onDrag(
        dy: Float,
        atTop: Boolean,
        isLoading: Boolean,
    ): Boolean {
        if (isLoading) {
            distance = 0f
            progress = 0f
            triggered = true
            return false
        }
        distance =
            when {
                // Downward at the top is the pull gesture itself…
                dy > 0f && atTop -> distance + dy
                // …downward mid-page is just page scrolling: never a pull.
                dy > 0f -> distance
                // Upward movement pulls the accumulated distance back down.
                else -> (distance + dy).coerceAtLeast(0f)
            }
        progress = (distance / thresholdPx).coerceIn(0f, 1f)
        val crossesThreshold = !triggered && distance >= thresholdPx
        triggered = triggered || crossesThreshold
        return crossesThreshold
    }
}
