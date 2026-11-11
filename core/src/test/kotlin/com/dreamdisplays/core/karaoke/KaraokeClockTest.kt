package com.dreamdisplays.core.karaoke

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KaraokeClockTest {
    private val ms = 1_000_000L

    @Test
    fun theFastestExchangeGivesTheOffset() {
        val clock = ClockOffsetEstimator()
        val offset = 7_300 * ms // client clock ahead
        val random = Random(1)
        var t = 1_000_000 * ms
        repeat(12) {
            // 30 ms each way, plus up to 16 ms waiting for the client's frame and 50 ms for the server's tick
            val clientWait = random.nextLong(0, 16 * ms)
            val serverWait = random.nextLong(0, 50 * ms)
            val t1 = t
            val t2 = t1 + 30 * ms + clientWait + offset
            val t3 = t2 + ms / 10
            val t4 = t3 - offset + 30 * ms + serverWait
            assertTrue(clock.onExchange(t1, t2, t3, t4))
            t += 2_000 * ms
        }
        val estimate = assertNotNull(clock.offsetNanos(t))
        val rtt = assertNotNull(clock.rttNanos(t))
        // The error is at most half the best round trip's queueing
        assertTrue(abs(estimate - offset) <= (rtt - 60 * ms) / 2 + ms, "error ${(estimate - offset) / 1e6} ms")
        assertTrue(abs(estimate - offset) < 15 * ms)
    }

    @Test
    fun exchangesAgeOutAndNonsenseIsRejected() {
        val clock = ClockOffsetEstimator(maxAgeNanos = 10_000 * ms)
        assertFalse(clock.onExchange(1_000 * ms, 500 * ms, 400 * ms, 1_100 * ms))
        assertTrue(clock.onExchange(1_000 * ms, 1_050 * ms, 1_050 * ms, 1_100 * ms))
        assertEquals(0L, clock.offsetNanos(2_000 * ms))
        assertNull(clock.offsetNanos(20_000 * ms))
    }

    @Test
    fun uplinkIsTheLowEndOfCaptureToArrival() {
        val meter = UplinkMeter()
        val offset = -2_000 * ms // client clock behind
        val random = Random(2)
        var capture = 50_000 * ms // server time of capture
        for (seq in 0L until 300L) {
            val arrival = capture + 70 * ms + random.nextLong(0, 30 * ms)
            meter.onFrame(seq, arrival)
            if (seq % 10 == 0L) meter.onMarker(seq, capture + offset, offset)
            capture += 20 * ms
        }
        val latency = assertNotNull(meter.latencyNanos())
        assertTrue(latency in 70 * ms..76 * ms, "latency ${latency / 1e6} ms")
    }

    @Test
    fun aReusedSequenceNumberDoesNotMatchAnOldFrame() {
        val meter = UplinkMeter()
        meter.onFrame(5, 1_000 * ms) // seq 5 of an earlier mic session
        meter.onFrame(5, 60_000 * ms + 80 * ms)
        assertEquals(80 * ms, meter.onMarker(5, 60_000 * ms, 0L))
        // A marker no frame fits
        assertNull(meter.onMarker(6, 60_000 * ms, 0L))
        assertNull(meter.latencyNanos())
    }
}
