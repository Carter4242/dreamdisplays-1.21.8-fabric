package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A display back in range opens where the shared clock is, and never shows its beginning first. */
class ReenterStartTest {
    @Test
    fun `a replayed playing timeline opens where the clock has got to, plus latency and the cold lead`() {
        // Recorded 3 s ago at 12 s: 15 s now, plus 40 ms latency and a 600 ms cold lead
        assertEquals(15_640, replayStartMs(12_000, false, -3_000, 1.0, 40, 600, loop = false, durationMs = 0))
        // At 2x the clock moved twice as far
        assertEquals(18_000 + 1_280, replayStartMs(12_000, false, -3_000, 2.0, 40, 600, loop = false, durationMs = 0))
        // Wraps into the loop
        assertEquals(640, replayStartMs(9_000, false, -1_000, 1.0, 40, 600, loop = true, durationMs = 10_000))
    }

    @Test
    fun `a paused timeline or a start still ahead opens on the target itself`() {
        assertEquals(12_000, replayStartMs(12_000, true, -3_000, 1.0, 40, 600, loop = false, durationMs = 0))
        assertEquals(0, replayStartMs(0, false, 1_500, 1.0, 40, 600, loop = false, durationMs = 0))
    }

    @Test
    fun `a start primed before the player existed is kept for that video and moves on with the clock`() {
        val s = 1_000_000L
        val pending = PendingStart("a", 15_000 * s, atNanos = 1_000 * s, ratePerMs = 1.0)
        assertEquals(15_250 * s, pending.positionFor("a", 1_250 * s))
        assertEquals(-1L, pending.positionFor("b", 1_250 * s))
        assertEquals(-1L, pending.positionFor("a", 1_000 * s + PendingStart.MAX_AGE_NANOS + 1))
        assertEquals(15_000 * s, PendingStart("a", 15_000 * s, 0L, ratePerMs = 0.0).positionFor("a", 5_000 * s))
    }

    @Test
    fun `the first timeline a player gets is checked for a far-off stream even after the picture stopped waiting`() {
        assertTrue(joinsFarOff(initialTimeline = true, firstForPlayer = false, paused = false, videoStarted = true))
        assertTrue(joinsFarOff(initialTimeline = false, firstForPlayer = true, paused = false, videoStarted = true))
        assertFalse(joinsFarOff(initialTimeline = false, firstForPlayer = false, paused = false, videoStarted = true))
        assertFalse(joinsFarOff(initialTimeline = true, firstForPlayer = true, paused = true, videoStarted = true))
        assertFalse(joinsFarOff(initialTimeline = true, firstForPlayer = true, paused = false, videoStarted = false))
    }
}
