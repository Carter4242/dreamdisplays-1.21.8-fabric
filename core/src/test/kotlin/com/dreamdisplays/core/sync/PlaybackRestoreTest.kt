package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A screen share ends: the previous video comes back paused where it was when the share started, at its speed. */
class PlaybackRestoreTest {
    @Test
    fun `a playing video is saved where it was and comes back paused there`() {
        // Playing since wall 1 000 from 60 s at 1.5x: at wall 11 000 it is at 60 s + 15 s
        val playing = Timeline(60_000, 1_000, paused = false, durationMs = 600_000, loop = true, rate = 1.5)
        val saved = SavedPlayback.of(playing, 11_000)!!
        assertEquals(SavedPlayback(75_000, 1.5), saved)
        // The share ran for ten minutes: nothing moved meanwhile
        val restored = saved.restoredTimeline(nowMs = 611_000, durationMs = 600_000)
        assertTrue(restored.paused)
        assertEquals(75_000, restored.positionAt(700_000))
        assertEquals(1.5, restored.rate)
        assertEquals(600_000, restored.durationMs)
        assertTrue(restored.loop)
    }

    @Test
    fun `a paused video stays paused at its position`() {
        val paused = Timeline(42_000, 1_000, paused = true, durationMs = 90_000, loop = true)
        val restored = SavedPlayback.of(paused, 50_000)!!.restoredTimeline(70_000, 90_000)
        assertTrue(restored.paused)
        assertEquals(42_000, restored.positionMs)
    }

    @Test
    fun `a video still in its start hold or scheduled ahead comes back at its start`() {
        val scheduled = Timeline(0, 20_000, paused = false, durationMs = 90_000, loop = true)
        assertEquals(0, SavedPlayback.of(scheduled, 19_900)!!.positionMs)
    }

    @Test
    fun `a position past a length learned since is kept inside it, and no timeline means nothing to restore`() {
        assertEquals(30_000, SavedPlayback(45_000, 1.0).restoredTimeline(0, 30_000).positionMs)
        assertEquals(45_000, SavedPlayback(45_000, 1.0).restoredTimeline(0, 0).positionMs)
        assertNull(SavedPlayback.of(null, 0))
    }
}
