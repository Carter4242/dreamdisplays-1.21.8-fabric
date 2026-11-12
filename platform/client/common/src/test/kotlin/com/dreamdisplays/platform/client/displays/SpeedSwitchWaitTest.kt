package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedSwitchWaitTest {
    @Test
    fun `a packet handled late by a game stall switches when the estimate reaches the switch position`() {
        // The estimate is 400 ms of content short of the switch at 1x; the packet, handled late, says 700 ms
        assertEquals(400.0, switchWaitMs(12_400, 12_000.0, 1.0, true, false, 0, handledWaitMs = 700.0, startInMs = 400))
        // At 2x the estimate gets there in half the time
        assertEquals(200.0, switchWaitMs(12_400, 12_000.0, 2.0, true, false, 0, handledWaitMs = 500.0, startInMs = 400))
        // Its own wait already passed: the estimate says the switch was 300 ms ago
        assertEquals(-300.0, switchWaitMs(12_400, 12_700.0, 1.0, true, false, 0, handledWaitMs = 100.0, startInMs = 400))
    }

    @Test
    fun `an estimate that has fallen behind takes the packet's earlier instant, as it would any fresher packet`() {
        assertEquals(120.0, switchWaitMs(12_400, 12_000.0, 1.0, true, false, 0, handledWaitMs = 120.0, startInMs = 400))
    }

    @Test
    fun `positions across the loop are measured the short way round`() {
        // 60 s loop: the estimate is unwrapped one play-through on
        assertEquals(400.0, switchWaitMs(12_400, 72_000.0, 1.0, true, true, 60_000, handledWaitMs = 900.0, startInMs = 400))
    }

    @Test
    fun `without a playing estimate, or one that disagrees by more than a second, the packet's own wait counts`() {
        assertEquals(250.0, switchWaitMs(12_400, 12_000.0, 1.0, false, false, 0, handledWaitMs = 250.0, startInMs = 400))
        assertEquals(250.0, switchWaitMs(12_400, 9_000.0, 1.0, true, false, 0, handledWaitMs = 250.0, startInMs = 400))
        assertEquals(250.0, switchWaitMs(12_400, 14_000.0, 1.0, true, false, 0, handledWaitMs = 250.0, startInMs = 400))
    }
}

class SpeedSwitchCorrectionTest {
    @Test
    fun `a player further off than a cheap seek after a speed switch is seeked onto the shared clock`() {
        kotlin.test.assertTrue(switchNeedsSeek(713.0, correctiveSeeks = true, seekThresholdMs = 1_500.0))
        kotlin.test.assertTrue(switchNeedsSeek(-350.0, correctiveSeeks = true, seekThresholdMs = 1_500.0))
        kotlin.test.assertFalse(switchNeedsSeek(250.0, correctiveSeeks = true, seekThresholdMs = 1_500.0))
        // Corrective seeks off by policy: never
        kotlin.test.assertFalse(switchNeedsSeek(2_000.0, correctiveSeeks = false, seekThresholdMs = 1_500.0))
        // A lower configured threshold counts
        kotlin.test.assertTrue(switchNeedsSeek(250.0, correctiveSeeks = true, seekThresholdMs = 200.0))
    }
}
