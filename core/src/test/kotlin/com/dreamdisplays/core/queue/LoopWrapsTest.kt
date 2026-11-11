package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopWrapsTest {
    private val duration = 90_000L
    private val playing = Timeline(0, 1_000, paused = false, durationMs = duration, loop = true)

    @Test
    fun `a stall across the end shows as a later play-through`() {
        // Last tick 300 ms before the end (91 000), next one 200 ms after it: the restart window was skipped
        val before = LoopWraps.playThrough(playing, 90_700, duration)
        val after = LoopWraps.playThrough(playing, 91_200, duration)
        assertEquals(0L, before)
        assertEquals(1L, after)
        assertTrue(LoopWraps.missedWrap(before, after))
        // Re-anchored where it stands: 200 ms into the next play-through
        assertEquals(200L, LoopWraps.reanchored(playing, 91_200).positionMs)
    }

    @Test
    fun `ticks within one play-through, or a new timeline, are no missed wrap`() {
        assertFalse(LoopWraps.missedWrap(0L, LoopWraps.playThrough(playing, 50_000, duration)))
        assertFalse(LoopWraps.missedWrap(null, 3L))
    }

    @Test
    fun `timelines that do not wrap by themselves have no play-through`() {
        assertNull(LoopWraps.playThrough(playing.copy(paused = true), 91_200, duration))
        assertNull(LoopWraps.playThrough(playing.copy(loop = false), 91_200, duration))
        // Its own length unknown: the restart path handles it
        assertNull(LoopWraps.playThrough(playing.copy(durationMs = 0), 91_200, duration))
        // A restart scheduled ahead is still in its first play-through
        assertEquals(0L, LoopWraps.playThrough(Timeline(0, 91_000, paused = false, durationMs = duration, loop = true), 90_950, duration))
    }
}
