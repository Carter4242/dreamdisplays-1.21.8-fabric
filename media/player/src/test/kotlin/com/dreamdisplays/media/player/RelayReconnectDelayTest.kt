package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayReconnectDelayTest {
    @Test
    fun `a share that is starting is tried again quickly, then at the usual pace`() {
        assertEquals(300L, MediaPlayer.relayReconnectDelayMs(everOnAir = false, sinceStartNanos = 500_000_000L))
        assertEquals(300L, MediaPlayer.relayReconnectDelayMs(everOnAir = false, sinceStartNanos = 3_900_000_000L))
        // Off the air for a while (sharer not there yet, or gone): one attempt a second, as before
        assertEquals(1_000L, MediaPlayer.relayReconnectDelayMs(everOnAir = false, sinceStartNanos = 4_000_000_000L))
        // Was on the air: a drop reconnects at the usual pace
        assertEquals(1_000L, MediaPlayer.relayReconnectDelayMs(everOnAir = true, sinceStartNanos = 1_000_000_000L))
    }
}
