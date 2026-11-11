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

    @Test
    fun `the shared clock wraps at the server timeline's length, not the media's`() {
        assertEquals(11_150, followLoopMs(localMs = 10_000, serverMs = 11_150))
        assertEquals(180_150, followLoopMs(localMs = 360_000, serverMs = 180_150))
        assertEquals(10_000, followLoopMs(localMs = 10_000, serverMs = 0))
        assertEquals(0, followLoopMs(localMs = 0, serverMs = 0))
    }

    @Test
    fun `drift is the short way round the loop, except past the server's loop end`() {
        // Just before the end while the clock already restarted: barely behind
        assertEquals(150, loopDrift(targetMs = 50, localMs = 11_050, loop = true, periodMs = 11_150))
        // Media longer than the server's loop (60 s): 20 s into the tail while everyone restarted 20 s ago is far off
        assertEquals(-60_000, loopDrift(targetMs = 20_000, localMs = 80_000, loop = true, periodMs = 60_000))
        // Not looping: as it is
        assertEquals(-70_000, loopDrift(targetMs = 10_000, localMs = 80_000, loop = false, periodMs = 60_000))
        assertEquals(-30, loopDrift(targetMs = 59_990, localMs = 60_020, loop = true, periodMs = 60_000))
    }

    @Test
    fun `a whole-second clip held at its end waits for the server's restart instead of restarting itself`() {
        // Media 10 s, server loops at 11.15 s (whole-second slack + end safety)
        val period = followLoopMs(10_000, 11_150)
        for (clock in longArrayOf(10_000, 10_500, 11_000, 11_149)) {
            val target = wrapTimeline(clock, true, period)
            assertFalse(heldEndRestarts(target, period, hold), "restarted itself at server clock $clock")
        }
        // Once the server's clock wraps, it starts over (the restart packet was late)
        assertTrue(heldEndRestarts(wrapTimeline(11_160, true, period), period, hold))
    }

    @Test
    fun `a seek into the server's tail past the media's end holds near the end instead of replaying the opening`() {
        assertEquals(9_500, clampTimelineSeek(10_400, loop = true, periodMs = 11_150, endMs = 10_000, marginMs = 500))
        assertEquals(9_800, clampTimelineSeek(9_800, loop = true, periodMs = 11_150, endMs = 10_000, marginMs = 500))
        assertEquals(300, clampTimelineSeek(11_450, loop = true, periodMs = 11_150, endMs = 10_000, marginMs = 500))
        assertEquals(9_500, clampTimelineSeek(12_000, loop = false, periodMs = 11_150, endMs = 10_000, marginMs = 500))
        assertEquals(400, clampTimelineSeek(11_550, loop = true, periodMs = 11_150, endMs = 0, marginMs = 500))
        assertEquals(0, clampTimelineSeek(-5, loop = false, periodMs = 0, endMs = 0, marginMs = 500))
    }

    @Test
    fun `a restart from the end that ends again at once confirms where the media really ends`() {
        // Header (and server) 6:00, media ends at 3:00: the reopen at 3:20 ends without running, or within the probe
        assertTrue(endedRightAfterRestart(restartFromMs = 200_000, endedAtMs = -1, probeMs = hold))
        assertTrue(endedRightAfterRestart(restartFromMs = 200_000, endedAtMs = 200_400, probeMs = hold))
        // A loop that played through is no such confirmation
        assertFalse(endedRightAfterRestart(restartFromMs = 600, endedAtMs = 179_900, probeMs = hold))
    }

    @Test
    fun `a player held at its confirmed end waits for the clock to come round instead of reopening every second`() {
        val bound = heldEndBoundMs(periodMs = 360_150, confirmedEndMs = 180_000)
        assertEquals(180_000, bound)
        assertFalse(heldEndRestarts(200_000, bound, hold))
        assertFalse(heldEndRestarts(359_000, bound, hold))
        assertTrue(heldEndRestarts(500, bound, hold))
        assertTrue(heldEndRestarts(100_000, bound, hold)) // Seeked back before it
        assertEquals(10_150, heldEndBoundMs(periodMs = 10_150, confirmedEndMs = 0))
        assertEquals(10_150, heldEndBoundMs(periodMs = 10_150, confirmedEndMs = 12_000))
        assertEquals(9_000, heldEndBoundMs(periodMs = 0, confirmedEndMs = 9_000))
    }
}

