package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class KaraokePcmTapTimingTest {
    @Test
    fun audioQueuedAheadOfTheLinePositionLeavesThatMuchLater() {
        // 4410 frames queued ahead at 44.1 kHz = 100 ms
        assertEquals(1_100_000_000L, PcmTapTiming.playAtNanos(1_000_000_000L, 14_410, 10_000, 44_100))
        // At 48 kHz the same frame count is shorter
        assertEquals(1_000_000_000L + 4_800 * 1_000_000_000L / 48_000, PcmTapTiming.playAtNanos(1_000_000_000L, 14_800, 10_000, 48_000))
    }

    @Test
    fun audioAlreadyPlayingWasHeardEarlier() {
        assertEquals(990_000_000L, PcmTapTiming.playAtNanos(1_000_000_000L, 9_559, 10_000, 44_100))
    }
}
