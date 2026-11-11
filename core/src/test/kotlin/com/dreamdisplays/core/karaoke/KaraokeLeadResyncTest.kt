package com.dreamdisplays.core.karaoke

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.toSync
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Starting or stopping to sing: one timeline, to the singer only, playing as the display plays. */
class KaraokeLeadResyncTest {
    private val display = UUID.randomUUID()
    private val singer = UUID.randomUUID()
    private val listeners = List(5) { UUID.randomUUID() }
    private val nearby = listeners + singer
    private val playing = Timeline(positionMs = 30_000, serverTimeMs = 100_000, paused = false, durationMs = 240_000, loop = true)
        .toSync(display, PlaybackMode.SYNCED, nowMs = 101_000)

    @Test
    fun addingASingerSendsExactlyOnePlayingSyncToThemAlone() {
        val packets = LeadResync.packetsFor(singer, nearby, playing, 350)
        assertEquals(1, packets.size)
        val (target, sync) = packets.single()
        assertEquals(singer, target)
        assertFalse(sync.isPaused)
        assertEquals(playing.currentTimeMs + 350, sync.currentTimeMs)
        assertEquals(playing.serverTimeMs, sync.serverTimeMs)
        assertEquals(350, sync.karaokeLeadMs)
        assertTrue(packets.none { it.first in listeners })
    }

    @Test
    fun removingASingerSendsExactlyOneUnshiftedPlayingSyncToThemAlone() {
        val packets = LeadResync.packetsFor(singer, nearby, playing, 0)
        assertEquals(listOf(singer to playing), packets)
        assertFalse(packets.single().second.isPaused)
        assertEquals(0, packets.single().second.karaokeLeadMs)
    }

    @Test
    fun aSingerAwayFromTheDisplayOrADisplayWithoutTimelineGetsNothing() {
        assertTrue(LeadResync.packetsFor(singer, listeners, playing, 350).isEmpty())
        assertTrue(LeadResync.packetsFor(singer, nearby, null, 350).isEmpty())
    }

    @Test
    fun aPausedDisplayStaysPausedButIsNeverPausedForTheSwitch() {
        val paused = playing.copy(isPaused = true)
        assertTrue(LeadResync.packetsFor(singer, nearby, paused, 350).single().second.isPaused)
        assertFalse(LeadResync.packetsFor(singer, nearby, playing, 350).single().second.isPaused)
    }
}
