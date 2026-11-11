package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A synced display with nothing queued restarts its video at the end, for everyone, at the end instant. */
class LoopRestartTest {
    private val duration = 90_000L

    /** A looping timeline started at wall time 1 000: its first end is at wall 91 000. */
    private val playing = Timeline(0, 1_000, paused = false, durationMs = duration, loop = true)

    @Test
    fun `restart is scheduled at the exact end instant, only once it is near`() {
        assertNull(QueueTiming.loopRestartAtMs(playing, 90_000, earlyMs = 150))
        assertEquals(91_000L, QueueTiming.loopRestartAtMs(playing, 90_900, earlyMs = 150))
        // The second play-through ends one duration later
        assertEquals(181_000L, QueueTiming.loopRestartAtMs(playing, 180_880, earlyMs = 150))
        // At double speed the content left takes half as long
        val fast = Timeline(89_800, 10_000, paused = false, durationMs = duration, loop = true, rate = 2.0)
        assertEquals(10_100L, QueueTiming.loopRestartAtMs(fast, 10_000, earlyMs = 150))
    }

    @Test
    fun `a wrapping timeline is never restarted mid-video by a late tick`() {
        // 2 s after a missed end it wrapped by itself: not due again until its next end
        assertNull(QueueTiming.loopRestartAtMs(playing, 93_000, earlyMs = 150))
    }

    @Test
    fun `a clock that ran past an end nobody wrapped restarts now`() {
        // No duration on the timeline itself (a restart dropped it), the display knows 90 s: 42 s past the end
        val unwrapped = Timeline(0, 1_000, paused = false, durationMs = 0, loop = false)
        assertEquals(133_000L, QueueTiming.loopRestartAtMs(unwrapped, 133_000, earlyMs = 150, durationMs = duration))
        // Before its end it is scheduled at the end like any other
        assertEquals(91_000L, QueueTiming.loopRestartAtMs(unwrapped, 90_900, earlyMs = 150, durationMs = duration))
        assertNull(QueueTiming.loopRestartAtMs(unwrapped, 50_000, earlyMs = 150, durationMs = duration))
    }

    @Test
    fun `paused, unknown length or a start still ahead are left alone`() {
        assertNull(QueueTiming.loopRestartAtMs(playing.copy(paused = true), 90_900, earlyMs = 150))
        assertNull(QueueTiming.loopRestartAtMs(Timeline(0, 1_000, paused = false), 500_000, earlyMs = 150))
        // Just restarted: anchored at its start instant, a full play-through ahead
        val restarted = Timeline(0, 91_000, paused = false, durationMs = duration, loop = true)
        assertNull(QueueTiming.loopRestartAtMs(restarted, 90_950, earlyMs = 150))
    }
}
