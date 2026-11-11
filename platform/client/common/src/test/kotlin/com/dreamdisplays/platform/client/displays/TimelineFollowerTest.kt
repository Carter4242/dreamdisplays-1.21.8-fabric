package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineFollowerTest {
    private val hold = 2_000L

    @Test
    fun `a long video held at its end restarts once the clock is back past the hold margin`() {
        assertFalse(heldEndRestarts(179_000, 180_000, hold))
        assertTrue(heldEndRestarts(500, 180_000, hold))
    }

    @Test
    fun `a loop shorter than the hold margin restarts when the clock wraps`() {
        for (duration in longArrayOf(400, 1_500, 2_000, 3_000)) {
            assertTrue(heldEndRestarts(0, duration, hold), "$duration ms loop never restarts at 0.")
            assertTrue(heldEndRestarts(duration / 2 - 1, duration, hold), "$duration ms loop at its middle.")
            assertFalse(heldEndRestarts(duration - 1, duration, hold), "$duration ms loop restarts at its end.")
        }
    }

    @Test
    fun `unknown duration never restarts`() {
        assertFalse(heldEndRestarts(0, 0, hold))
    }

    @Test
    fun `a pause stamped before a scheduled start instant still applies after the start fired`() {
        val packetStamp = 10_000L
        val leadMs = 300L
        // The start fires keeping its packet's stamp; the server's pause came 10 ms before the start instant
        val lastAfterFire = packetStamp
        assertFalse(isStaleTimeline(packetStamp + leadMs - 10, lastAfterFire))
        // The scheduled start itself is not stale against its own hold
        assertFalse(isStaleTimeline(packetStamp, packetStamp))
    }

    @Test
    fun `an older timeline is dropped and an unstamped one is always taken`() {
        assertTrue(isStaleTimeline(9_999, 10_000))
        assertFalse(isStaleTimeline(0, 10_000))
        assertFalse(isStaleTimeline(10_001, 10_000))
    }
}
