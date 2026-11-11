package com.dreamdisplays.core.sync

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The estimator + drift controller loop (as `TimelineFollower` runs it) at playback speeds other than 1. */
class SpeedSyncTest {
    @Test
    fun estimatorProjectsAtTheTimelineRate() {
        val e = TimelineEstimator()
        e.onPacket(positionMs = 10_000, paused = false, nowMs = 1_000.0, latencyCompMs = 50.0, rate = 2.0)
        // 50 ms of latency at 2x already passed 100 ms of content
        assertEquals(10_100.0, e.targetMs(1_000.0), 1e-6)
        assertEquals(12_100.0, e.targetMs(2_000.0), 1e-6)
    }

    @Test
    fun aRateChangeStartsANewSegment() {
        val e = TimelineEstimator()
        e.onPacket(10_000, false, 1_000.0, 0.0, rate = 1.0)
        assertFalse(e.onPacket(11_000, false, 2_000.0, 0.0, rate = 1.0))
        assertTrue(e.onPacket(11_000, false, 2_000.0, 0.0, rate = 1.5))
        assertEquals(12_500.0, e.targetMs(3_000.0), 1e-6)
    }

    @Test
    fun handlingJitterAtHighSpeedIsNotASeek() {
        val e = TimelineEstimator()
        e.onPacket(0, false, 0.0, 0.0, rate = 10.0)
        // A packet handled 80 ms late looks 800 ms of content early: still the same segment at 10x
        assertFalse(e.onPacket(50_000 - 800, false, 5_000.0, 0.0, rate = 10.0))
    }

    @Test
    fun driftIsJudgedInRealTime() {
        val e = TimelineEstimator()
        e.onPacket(0, false, 0.0, 0.0, rate = 4.0)
        assertEquals(500.0, e.toRealMs(2_000.0), 1e-9)
        e.onPacket(0, false, 0.0, 0.0, rate = 0.5)
        assertEquals(400.0, e.toRealMs(200.0), 1e-9)
        e.onPacket(0, true, 0.0, 0.0, rate = 0.5)
        assertEquals(200.0, e.toRealMs(200.0), 1e-9) // Paused: no pace, content as is
    }

    /** Runs a player that started [initialOffsetMs] (content) behind a server timeline at [rate] for [seconds]. */
    private fun simulate(rate: Double, initialOffsetMs: Double, seconds: Int): Pair<Double, Int> {
        val estimator = TimelineEstimator()
        val controller = DriftController()
        var serverMs = 60_000.0
        var localMs = serverMs - initialOffsetMs
        var ratio = 1.0
        var seeks = 0
        val tickMs = 50.0
        var now = 0.0
        while (now < seconds * 1_000.0) {
            if (now.toLong() % 5_000L == 0L) estimator.onPacket(serverMs.toLong(), false, now, 0.0, rate)
            val drift = estimator.toRealMs(estimator.targetMs(now) - localMs)
            val decision = controller.update(drift, now)
            if (decision.seek) {
                localMs = estimator.targetMs(now)
                seeks++
            } else {
                ratio = decision.ratio
            }
            now += tickMs
            serverMs += tickMs * rate
            localMs += tickMs * rate * ratio // Speed and the small sync correction multiply
        }
        return (estimator.targetMs(now) - localMs) / rate to seeks
    }

    @Test
    fun smallDriftClosesWithoutSeekingAtAnySpeed() {
        for (rate in listOf(0.1, 0.5, 1.0, 2.0, 10.0)) {
            // 300 ms of real-time offset whatever the speed (the content gap is that much times the rate)
            val (finalDrift, seeks) = simulate(rate, initialOffsetMs = 300.0 * rate, seconds = 90)
            assertEquals(0, seeks, "rate $rate seeked")
            assertTrue(abs(finalDrift) < 40.0, "rate $rate left ${finalDrift} ms of drift")
        }
    }

    @Test
    fun largeDriftSeeksOnceAtAnySpeed() {
        for (rate in listOf(0.1, 2.0, 10.0)) {
            val (finalDrift, seeks) = simulate(rate, initialOffsetMs = 5_000.0 * rate, seconds = 30)
            assertEquals(1, seeks, "rate $rate")
            assertTrue(abs(finalDrift) < 40.0, "rate $rate left ${finalDrift} ms of drift")
        }
    }
}
