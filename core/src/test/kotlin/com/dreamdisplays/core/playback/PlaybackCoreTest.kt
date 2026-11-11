package com.dreamdisplays.core.playback

import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCoreTest {
    @Test
    fun runningTimelineAdvancesWithWallClock() {
        val t = Timeline(positionMs = 1_000, serverTimeMs = 10_000, paused = false)
        assertEquals(1_000, t.positionAt(10_000))
        assertEquals(3_500, t.positionAt(12_500))
    }

    @Test
    fun pausedTimelineIsFrozen() {
        val t = Timeline(positionMs = 5_000, serverTimeMs = 10_000, paused = true)
        assertEquals(5_000, t.positionAt(99_999))
    }

    @Test
    fun loopWrapsAroundDuration() {
        val t = Timeline(positionMs = 0, serverTimeMs = 0, paused = false, durationMs = 1_000, loop = true)
        assertEquals(500, t.positionAt(2_500))
        assertEquals(0, t.positionAt(3_000))
    }

    @Test
    fun pauseKeepsPositionContinuous() {
        val running = Timeline(positionMs = 0, serverTimeMs = 0, paused = false)
        val paused = running.withPaused(true, nowMs = 4_000)
        assertEquals(4_000, paused.positionMs)
        assertEquals(4_000, paused.positionAt(99_999))
    }

    private fun ctx(
        mode: PlaybackMode,
        owner: Boolean = false,
        admin: Boolean = false,
        locked: Boolean = true,
    ) = PlaybackContext(mode, owner, admin, locked)

    @Test
    fun localControlsRespectLock() {
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.LOCAL, owner = false, locked = false)))
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.LOCAL, owner = false, locked = true)))
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.LOCAL, owner = true, locked = true)))
    }

    @Test
    fun syncedNeedsEditor() {
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.SYNCED, owner = true)))
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.SYNCED, locked = false)))
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.SYNCED, locked = true)))
    }

    @Test
    fun broadcastLocksEverythingDown() {
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.BROADCAST, owner = true)))
        assertFalse(PlaybackPermissions.canChangeQuality(ctx(PlaybackMode.BROADCAST, owner = true)))
        assertFalse(PlaybackPermissions.canToggleLock(ctx(PlaybackMode.BROADCAST, admin = true)))
        assertTrue(PlaybackPermissions.isEffectivelyLocked(PlaybackMode.BROADCAST, baseLocked = false))
    }

    @Test
    fun modeWireValuesStayPut() {
        // Stored in the displays table and sent on the wire; 2 was the removed watch-party mode
        assertEquals(listOf(0, 1, 3), PlaybackMode.entries.map { it.wire })
        assertEquals(PlaybackMode.BROADCAST, PlaybackMode.fromWire(3))
        assertEquals(PlaybackMode.LOCAL, PlaybackMode.fromWire(2))
    }
}
