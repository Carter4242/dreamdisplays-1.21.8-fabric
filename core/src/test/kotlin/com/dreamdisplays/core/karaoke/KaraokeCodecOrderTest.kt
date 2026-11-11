package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.karaoke.KaraokeCodecOrder.Admit
import kotlin.test.Test
import kotlin.test.assertEquals

class KaraokeCodecOrderTest {
    @Test
    fun framesInOrderAreTakenAndTheFirstOfABurstResets() {
        val order = KaraokeCodecOrder()
        assertEquals(Admit.NEW_BURST, order.admit(1, 10))
        assertEquals(Admit.TAKE, order.admit(1, 11))
        // A lost frame leaves a gap, but what comes after is still in order
        assertEquals(Admit.TAKE, order.admit(1, 13))
    }

    @Test
    fun aReorderedOrRepeatedFrameIsDropped() {
        val order = KaraokeCodecOrder()
        order.admit(1, 20)
        assertEquals(Admit.TAKE, order.admit(1, 22))
        assertEquals(Admit.DROP, order.admit(1, 21))
        assertEquals(Admit.DROP, order.admit(1, 22))
        assertEquals(Admit.TAKE, order.admit(1, 23))
    }

    @Test
    fun aNewerBurstResetsAndAnOlderBurstsStragglerIsDropped() {
        val order = KaraokeCodecOrder()
        order.admit(1, 100)
        order.admit(1, 101)
        // A restarted count: lower numbers, but a newer burst
        assertEquals(Admit.NEW_BURST, order.admit(2, 0))
        assertEquals(Admit.TAKE, order.admit(2, 1))
        assertEquals(Admit.DROP, order.admit(1, 102))
    }
}
