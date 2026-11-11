package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The queue never switches before the real end of the media, whatever precision its duration came in. */
class QueueDurationTest {
    @Test
    fun `whole-second metadata gets a second of slack, a client ms duration only the safety margin`() {
        assertEquals(15_000L + 1_000L + QueueTiming.END_SAFETY_MS, QueueTiming.scheduledDurationMs(15_000))
        assertEquals(15_812L + QueueTiming.END_SAFETY_MS, QueueTiming.scheduledDurationMs(15_812))
        assertEquals(0L, QueueTiming.scheduledDurationMs(0))
    }

    @Test
    fun `the largest client report wins, and a short first report never sticks`() {
        // No queue entry: the largest report, whatever came first
        assertEquals(15_000L, QueueTiming.mediaDurationMs(0, listOf(15_000)))
        assertEquals(15_812L, QueueTiming.mediaDurationMs(0, listOf(15_000, 15_812)))
        assertEquals(212_400L, QueueTiming.mediaDurationMs(0, listOf(30_000, 212_400)))
        assertEquals(0L, QueueTiming.mediaDurationMs(0, emptyList()))
        // Never below what was settled on before for this video
        assertEquals(15_812L, QueueTiming.mediaDurationMs(0, listOf(15_400), floorMs = 15_812))
    }

    @Test
    fun `a queue entry's duration is the least the video runs, and bounds the reports`() {
        // A short report (previous video, partial stream) is ignored in favour of the entry
        assertEquals(213_000L, QueueTiming.mediaDurationMs(213_000, listOf(30_000)))
        // A precise report within the entry's rounding refines it upward
        assertEquals(213_640L, QueueTiming.mediaDurationMs(213_000, listOf(30_000, 213_640)))
        // A report far beyond the entry is another video's
        assertEquals(213_000L, QueueTiming.mediaDurationMs(213_000, listOf(600_000)))
        assertEquals(
            213_000L + 1_000L + QueueTiming.MAX_DURATION_RAISE_MS,
            QueueTiming.mediaDurationMs(213_000, listOf(213_000L + 1_000L + QueueTiming.MAX_DURATION_RAISE_MS)),
        )
        // With no report yet the entry alone
        assertEquals(213_000L, QueueTiming.mediaDurationMs(213_000, emptyList()))
    }

    @Test
    fun `a Short with 15 s metadata and 15_8 s of media is not cut`() {
        val mediaEndMs = 15_800L
        for (durationMs in listOf(15_000L, 15_800L)) {
            val scheduled = QueueTiming.scheduledDurationMs(durationMs)
            val playing = Timeline(0, 1_000, paused = false, durationMs = scheduled, loop = true)
            // The media ends at wall 16 800: not due just before it
            assertFalse(QueueTiming.advanceDue(playing, 1_000 + mediaEndMs - 100, earlyMs = 0))
            val switchAt = QueueTiming.switchAtMs(playing, 1_000 + scheduled - 100, earlyMs = 400)!!
            assertTrue(switchAt >= 1_000 + mediaEndMs + QueueTiming.END_SAFETY_MS)
            assertTrue(switchAt <= 1_000 + mediaEndMs + 1_000 + QueueTiming.END_SAFETY_MS)
        }
    }

    @Test
    fun `a length that comes down below the clock ends the play-through instead of wrapping into the middle`() {
        // Running since wall 0 with a 6:00 header; at 3:30 the real length turns out to be 3:00
        val playing = Timeline(0, 0, paused = false, durationMs = 360_150, loop = true)
        val shorter = QueueTiming.withLength(playing, 180_150, nowMs = 210_000)
        assertFalse(shorter.loop)
        assertEquals(210_000L, shorter.positionMs)
        // Its end is due now: the restart (or the queue's switch) comes at once, from the beginning
        assertEquals(210_000L, QueueTiming.loopRestartAtMs(shorter, 210_000, earlyMs = 150))
        assertTrue(QueueTiming.advanceDue(shorter, 210_000, earlyMs = 0))
        // Still ahead of the clock: it keeps looping
        val longer = QueueTiming.withLength(playing, 400_150, nowMs = 210_000)
        assertTrue(longer.loop)
        assertEquals(400_150L, longer.durationMs)
    }

    @Test
    fun `a length learned before a scheduled start keeps the start where it was`() {
        // A queue switch scheduled for wall 10 300; a viewer reports the new video's length at 10 100
        val scheduled = Timeline(0, 10_300, paused = false, durationMs = 0, loop = true)
        val updated = QueueTiming.withLength(scheduled, 120_150, nowMs = 10_100)
        assertEquals(10_300L, updated.serverTimeMs)
        assertEquals(0L, updated.positionMs)
        assertEquals(120_150L, updated.durationMs)
        assertTrue(updated.loop)
        // Once started, a length update re-anchors where the clock is
        val started = QueueTiming.withLength(scheduled, 120_150, nowMs = 11_300)
        assertEquals(11_300L, started.serverTimeMs)
        assertEquals(1_000L, started.positionMs)
    }
}
