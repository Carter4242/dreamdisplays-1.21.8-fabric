package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `a resolving player is re-primed on a running timeline only`() {
        assertTrue(reprimesBeforeOpen(hasPlayer = true, videoStarted = false, hasTimeline = true, paused = false))
        assertFalse(reprimesBeforeOpen(hasPlayer = true, videoStarted = true, hasTimeline = true, paused = false))
        assertFalse(reprimesBeforeOpen(hasPlayer = true, videoStarted = false, hasTimeline = true, paused = true))
        assertFalse(reprimesBeforeOpen(hasPlayer = true, videoStarted = false, hasTimeline = false, paused = false))
        assertFalse(reprimesBeforeOpen(hasPlayer = false, videoStarted = false, hasTimeline = true, paused = false))
    }

    @Test
    fun `a joining player's first start is a cold landing unless its stream already runs`() {
        assertTrue(expectsJoinLanding(initialTimeline = true, paused = false, clockRunning = false, landingPending = false))
        assertFalse(expectsJoinLanding(initialTimeline = true, paused = false, clockRunning = true, landingPending = false))
        assertFalse(expectsJoinLanding(initialTimeline = false, paused = false, clockRunning = false, landingPending = false))
        assertFalse(expectsJoinLanding(initialTimeline = true, paused = true, clockRunning = false, landingPending = false))
        assertFalse(expectsJoinLanding(initialTimeline = true, paused = false, clockRunning = false, landingPending = true))
    }

    @Test
    fun `a player with no length of its own restarts at its end on the server timeline's length`() {
        assertEquals(180_000, followDurationMs(localMs = 180_000, serverMs = 200_000))
        assertEquals(200_000, followDurationMs(localMs = 0, serverMs = 200_000))
        assertEquals(0, followDurationMs(localMs = 0, serverMs = 0))
        assertTrue(heldEndRestarts(500, followDurationMs(0, 200_000), hold))
    }
}
