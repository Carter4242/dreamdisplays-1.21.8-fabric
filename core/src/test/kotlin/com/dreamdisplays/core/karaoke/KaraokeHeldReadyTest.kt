package com.dreamdisplays.core.karaoke

import com.dreamdisplays.shared.playback.ReadinessRoster
import com.dreamdisplays.shared.playback.ReadinessState
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** A skip onto a song the singer preloaded: the held start waits for them at their lead, so that is where they count. */
class KaraokeHeldReadyTest {
    private val display = UUID(1, 1)
    private val singer = UUID(0, 1)
    private val listener = UUID(0, 2)
    private val duration = 200_000L

    @Test
    fun `a singer's preload counts at their lead position, everyone else's at 0`() {
        assertEquals(0L, KaraokeTiming.heldReadyPositionMs(0L, 1.0, duration))
        assertEquals(600L, KaraokeTiming.heldReadyPositionMs(600L, 1.0, duration))
        assertEquals(1_200L, KaraokeTiming.heldReadyPositionMs(600L, 2.0, duration))
    }

    @Test
    fun `nobody is waited for when the singer and the listeners preloaded`() {
        val roster = ReadinessRoster()
        val lead = 600L
        roster.report(display, singer, ReadinessState.READY, KaraokeTiming.heldReadyPositionMs(lead, 1.0, duration), 0)
        roster.report(display, listener, ReadinessState.READY, KaraokeTiming.heldReadyPositionMs(0L, 1.0, duration), 0)
        // The server checks the singer at their lead position and everyone else at the start
        val singerAt = KaraokeTiming.leadPosition(0L, lead, 1.0, duration, loop = true)
        assertEquals(emptyList(), roster.waitingFor(display, singerAt, listOf(singer)))
        assertEquals(emptyList(), roster.waitingFor(display, 0L, listOf(listener)))
        // Marked at 0, as before, the singer held everyone for the whole start hold
        val old = ReadinessRoster()
        old.report(display, singer, ReadinessState.READY, 0L, 0)
        assertEquals(listOf(singer), old.waitingFor(display, singerAt, listOf(singer)))
    }
}
