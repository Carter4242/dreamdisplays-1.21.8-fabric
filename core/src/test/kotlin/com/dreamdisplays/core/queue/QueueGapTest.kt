package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `[queue] gap_ms`: the pause between a queued video ending by itself and the next one starting. */
class QueueGapTest {
    private val duration = 90_000L

    /** A looping synced timeline started at wall time 1 000 (server timelines always loop); it ends at 91 000. */
    private val playing = Timeline(0, 1_000, paused = false, durationMs = duration, loop = true)

    @Test
    fun `a natural end starts the next entry the gap after the end`() {
        assertEquals(91_200L, QueueTiming.switchAtMs(playing, 90_700, earlyMs = 400, gapMs = 200))
        assertEquals(93_000L, QueueTiming.switchAtMs(playing, 90_700, earlyMs = 400, gapMs = 2_000))
        // The tick that decides comes at the same time with or without a gap: the switch is sent before the end
        assertTrue(QueueTiming.advanceDue(playing, 90_700, earlyMs = 400))
        assertFalse(QueueTiming.advanceDue(playing, 90_500, earlyMs = 400))
    }

    @Test
    fun `gap 0 is the start at the end instant`() {
        assertEquals(91_000L, QueueTiming.switchAtMs(playing, 90_700, earlyMs = 400, gapMs = 0))
        assertEquals(QueueTiming.switchAtMs(playing, 90_700, 400), QueueTiming.switchAtMs(playing, 90_700, 400, gapMs = 0))
    }

    @Test
    fun `the gap is wall time, whatever the speed`() {
        // At double speed the 10 s left take 5 s: the end is at 15 000, the start 200 ms after it
        val fast = Timeline(80_000, 10_000, paused = false, durationMs = duration, loop = true, rate = 2.0)
        assertEquals(15_200L, QueueTiming.switchAtMs(fast, 14_800, earlyMs = 400, gapMs = 200))
    }

    @Test
    fun `a gap out of range is clamped`() {
        assertEquals(91_000L + QueueTiming.MAX_GAP_MS, QueueTiming.switchAtMs(playing, 90_700, 400, gapMs = 60_000))
        assertEquals(91_000L, QueueTiming.switchAtMs(playing, 90_700, 400, gapMs = -5))
        assertEquals(200L, QueueTiming.DEFAULT_GAP_MS)
    }

    @Test
    fun `an end already past starts at once, gap or not`() {
        // A late tick after the wrap: clients already play the finished video from its start again
        assertEquals(91_200L, QueueTiming.switchAtMs(playing, 91_200, earlyMs = 400, gapMs = 200))
        assertEquals(95_000L, QueueTiming.switchAtMs(playing.copy(loop = false), 95_000, earlyMs = 400, gapMs = 200))
    }

    @Test
    fun `a looping queue moves on with the gap at each end`() {
        val a = item("a")
        val b = item("b")
        val queue = DisplayQueue(listOf(b), loop = true, current = a)
        assertTrue(queue.hasNext())
        // Second play-through of the looping timeline ends one duration later (181 000)
        assertTrue(QueueTiming.advanceDue(playing, 180_800, earlyMs = 400))
        assertEquals(181_200L, QueueTiming.switchAtMs(playing, 180_800, earlyMs = 400, gapMs = 200))
        assertEquals(b, queue.advance())
        // The finished entry went back to the end, so the next end moves on again (with the gap again)
        assertEquals(listOf(a), queue.items)
        // A one-entry looping queue has nothing to move on to: the display restarts its video instead, no queue gap
        val single = DisplayQueue(loop = true, current = a)
        assertFalse(single.hasNext())
    }

    @Test
    fun `with no duration known there is no natural end and no start instant`() {
        val unknown = playing.copy(durationMs = 0)
        assertFalse(QueueTiming.advanceDue(unknown, 3_600_000, earlyMs = 400))
        assertNull(QueueTiming.switchAtMs(unknown, 90_700, earlyMs = 400, gapMs = 200))
        assertNull(QueueTiming.switchAtMs(playing.copy(paused = true), 90_700, earlyMs = 400, gapMs = 200))
        // The playing entry's own (metadata) duration stands in: the gap counts from the end that gives
        val entryMs = QueueTiming.scheduledDurationMs(89_000L) // whole seconds: +1 s slack, + safety
        val end = 1_000L + entryMs
        val startAt = QueueTiming.switchAtMs(unknown, end - 300, earlyMs = 400, durationMs = entryMs, gapMs = 200)
        assertNotNull(startAt)
        assertEquals(end + 200, startAt)
    }

    private fun item(id: String) = QueueItem(id, "https://example.com/$id.mp4", "", id, 0L, "", UUID(0, 1), "p", 0L)
}
