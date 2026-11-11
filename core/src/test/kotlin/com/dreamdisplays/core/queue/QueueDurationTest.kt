package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `the largest client report wins, within the raise bound`() {
        var first: Long? = null
        var current: Long? = null
        fun report(ms: Long) {
            QueueTiming.mergeReportedMs(current, first, ms)?.let {
                if (first == null) first = it
                current = it
            }
        }
        report(15_000) // metadata-rounded client
        assertEquals(15_000L, current)
        report(15_812) // precise client
        assertEquals(15_812L, current)
        report(15_400) // shorter: ignored
        assertEquals(15_812L, current)
        report(15_000 + QueueTiming.MAX_DURATION_RAISE_MS + 1) // implausible: ignored
        assertEquals(15_812L, current)
        assertNull(QueueTiming.mergeReportedMs(15_812, 15_000, 15_812))
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
}
