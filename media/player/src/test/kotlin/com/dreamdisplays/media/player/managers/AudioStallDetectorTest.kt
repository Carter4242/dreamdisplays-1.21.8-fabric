package com.dreamdisplays.media.player.managers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioStallDetectorTest {
    private val ms = 1_000_000L

    @Test
    fun `silent audio while video plays fires once, then again only after the retry delay`() {
        val d = AudioStallDetector(thresholdNanos = 1_000 * ms, retryNanos = 10_000 * ms)
        assertFalse(d.check(0L, true, lastAudioNanos = 0L, lastVideoNanos = 0L))
        assertFalse(d.check(900 * ms, true, 0L, 900 * ms))
        assertTrue(d.check(1_100 * ms, true, 0L, 1_100 * ms))
        assertFalse(d.check(5_000 * ms, true, 0L, 5_000 * ms))
        assertTrue(d.check(11_200 * ms, true, 0L, 11_200 * ms))
    }

    @Test
    fun `flowing audio never fires`() {
        val d = AudioStallDetector(thresholdNanos = 1_000 * ms)
        for (t in 0L..10_000L step 100L) assertFalse(d.check(t * ms, true, t * ms, t * ms))
    }

    @Test
    fun `a whole-session stall is left to the frame watchdog`() {
        val d = AudioStallDetector(thresholdNanos = 1_000 * ms)
        d.check(0L, true, 0L, 0L)
        assertFalse(d.check(3_000 * ms, true, 0L, 0L))
    }

    @Test
    fun `silence is measured from when sound became due, not from before a pause`() {
        val d = AudioStallDetector(thresholdNanos = 1_000 * ms)
        d.check(0L, true, 0L, 0L)
        assertFalse(d.check(500 * ms, false, 0L, 500 * ms)) // Paused / parked / muted for a long time
        assertFalse(d.check(60_000 * ms, true, 0L, 60_000 * ms)) // Resumed: armed now
        assertFalse(d.check(60_900 * ms, true, 0L, 60_900 * ms))
        assertTrue(d.check(61_100 * ms, true, 0L, 61_100 * ms))
    }
}
