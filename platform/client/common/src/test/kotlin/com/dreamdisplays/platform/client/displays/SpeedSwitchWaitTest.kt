package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedSwitchWaitTest {
    @Test
    fun `the switch takes effect when the estimate reaches the switch position`() {
        // The estimate is 400 ms of content short of the switch at 1x: 400 ms from now, whatever the packet's own wait
        assertEquals(400.0, switchWaitMs(12_400, 12_000.0, 1.0, true, false, 0, handledWaitMs = 120.0, startInMs = 400))
        // At 2x it gets there in half the time
        assertEquals(200.0, switchWaitMs(12_400, 12_000.0, 2.0, true, false, 0, handledWaitMs = 120.0, startInMs = 400))
    }

    @Test
    fun `a packet handled late by a game stall still switches on the shared clock`() {
        // Handled 700 ms after it arrived: its own wait already passed; the estimate says the switch was 300 ms ago
        assertEquals(-300.0, switchWaitMs(12_400, 12_700.0, 1.0, true, false, 0, handledWaitMs = -300.0, startInMs = 400))
    }

    @Test
    fun `positions across the loop are measured the short way round`() {
        // 60 s loop: the estimate is unwrapped one play-through on
        assertEquals(400.0, switchWaitMs(12_400, 72_000.0, 1.0, true, true, 60_000, handledWaitMs = 0.0, startInMs = 400))
    }

    @Test
    fun `without a playing estimate, or one that disagrees by more than a second, the packet's own wait counts`() {
        assertEquals(250.0, switchWaitMs(12_400, 12_000.0, 1.0, false, false, 0, handledWaitMs = 250.0, startInMs = 400))
        assertEquals(250.0, switchWaitMs(12_400, 9_000.0, 1.0, true, false, 0, handledWaitMs = 250.0, startInMs = 400))
        assertEquals(250.0, switchWaitMs(12_400, 14_000.0, 1.0, true, false, 0, handledWaitMs = 250.0, startInMs = 400))
    }
}
