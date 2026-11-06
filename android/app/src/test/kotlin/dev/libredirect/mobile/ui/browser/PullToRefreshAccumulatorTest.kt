package dev.libredirect.mobile.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PullToRefreshAccumulatorTest {
    private lateinit var accumulator: PullToRefreshAccumulator

    @Before
    fun setUp() {
        accumulator = PullToRefreshAccumulator(thresholdPx = 100f)
    }

    @Test
    fun `accumulates downward drag at the top and triggers once at the threshold`() {
        assertFalse(accumulator.onDrag(dy = 40f, atTop = true, isLoading = false))
        assertEquals(0.4f, accumulator.progress, 0.001f)
        assertTrue(accumulator.onDrag(dy = 60f, atTop = true, isLoading = false))
        assertEquals(1f, accumulator.progress, 0.001f)
        // Further dragging in the same gesture never re-triggers.
        assertFalse(accumulator.onDrag(dy = 50f, atTop = true, isLoading = false))
    }

    @Test
    fun `does not accumulate while the page is scrolled away from the top`() {
        assertFalse(accumulator.onDrag(dy = 80f, atTop = false, isLoading = false))
        assertEquals(0f, accumulator.progress, 0.001f)
        assertFalse(accumulator.onDrag(dy = 80f, atTop = true, isLoading = false))
        assertEquals(0.8f, accumulator.progress, 0.001f)
    }

    @Test
    fun `upward movement pulls the accumulated distance back down`() {
        accumulator.onDrag(dy = 60f, atTop = true, isLoading = false)
        accumulator.onDrag(dy = -30f, atTop = true, isLoading = false)
        assertEquals(0.3f, accumulator.progress, 0.001f)
        // Upward movement cannot go below zero.
        accumulator.onDrag(dy = -100f, atTop = true, isLoading = false)
        assertEquals(0f, accumulator.progress, 0.001f)
    }

    @Test
    fun `a load starting mid-gesture cancels the drag and suppresses the trigger`() {
        accumulator.onDrag(dy = 90f, atTop = true, isLoading = false)
        // A load starts mid-gesture (link tap, previous trigger): the drag
        // returns no trigger of its own and resets the pull.
        assertFalse(accumulator.onDrag(dy = 5f, atTop = true, isLoading = true))
        assertEquals(0f, accumulator.progress, 0.001f)
        // The rest of the same gesture cannot trigger another reload.
        assertFalse(accumulator.onDrag(dy = 500f, atTop = true, isLoading = true))
        assertFalse(accumulator.onDrag(dy = 500f, atTop = true, isLoading = false))
    }

    @Test
    fun `reset clears progress and re-arms the trigger for the next drag`() {
        assertTrue(accumulator.onDrag(dy = 150f, atTop = true, isLoading = false))
        accumulator.reset()
        assertEquals(0f, accumulator.progress, 0.001f)
        // A fresh drag can trigger again.
        assertTrue(accumulator.onDrag(dy = 100f, atTop = true, isLoading = false))
    }

    @Test
    fun `progress clamps at one beyond the threshold`() {
        accumulator.onDrag(dy = 1000f, atTop = true, isLoading = false)
        assertEquals(1f, accumulator.progress, 0.001f)
    }
}
