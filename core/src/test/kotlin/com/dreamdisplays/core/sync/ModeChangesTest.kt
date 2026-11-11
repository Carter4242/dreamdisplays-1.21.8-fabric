package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class ModeChangesTest {
    @Test
    fun `synced to broadcast keeps a paused 2x clock as it is`() {
        val paused = Timeline(30_000, 1_000, paused = true, durationMs = 90_000, loop = true, rate = 2.0)
        assertSame(paused, ModeChanges.timelineAfter(paused, sharedNow = true, positionMs = 0, nowMs = 5_000, durationMs = 90_000))
    }

    @Test
    fun `from local the clock starts playing at the given position`() {
        val fresh = ModeChanges.timelineAfter(null, sharedNow = true, positionMs = 12_000, nowMs = 5_000, durationMs = 90_000)!!
        assertEquals(Timeline(12_000, 5_000, paused = false, durationMs = 90_000, loop = true), fresh)
        assertFalse(fresh.paused)
    }

    @Test
    fun `to local there is no shared clock`() {
        assertNull(ModeChanges.timelineAfter(Timeline(0, 0, paused = false), sharedNow = false, positionMs = 0, nowMs = 0, durationMs = 0))
    }
}
