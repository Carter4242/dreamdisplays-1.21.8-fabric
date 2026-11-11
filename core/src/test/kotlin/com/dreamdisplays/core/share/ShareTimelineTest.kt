package com.dreamdisplays.core.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareTimelineTest {
    private val p = ShareTimeline.PERIOD_MICROS

    @Test
    fun `a share's timestamps stay under flv's 31-bit milliseconds`() {
        val start = 5 * p + p - 1_000L // just before an epoch boundary: the worst case
        val pts = start - ShareTimeline.epoch(start)
        assertTrue(pts < p)
        // Twelve days later still below 2^31 ms
        assertTrue((pts + p) / 1_000L < (1L shl 31))
    }

    @Test
    fun `a viewer puts a frame back on the server clock, across an epoch boundary`() {
        val epoch = 7 * p
        val capture = epoch + p - 40_000L // 40 ms before the next epoch
        val pts = capture - epoch
        assertEquals(capture, ShareTimeline.captureMicros(pts, capture + 85_000L))
        assertEquals(capture, ShareTimeline.captureMicros(pts, capture))
    }

    @Test
    fun `latencies use each client's offset and drop a sharer without the clock`() {
        val offset = 123_456_789_000L // client minus server, ns
        val captureServer = 9 * p + 5L * 86_400_000_000L // five days into the period
        val pts = captureServer - ShareTimeline.epoch(captureServer)
        val shownClientNanos = (captureServer + 70_000L) * 1_000L + offset
        assertEquals(listOf(70_000L), ShareTimeline.latenciesMicros(listOf(pts to shownClientNanos), offset))
        // An older sharer, stamping from its own start (1.5 s in): days off, left out
        assertEquals(emptyList(), ShareTimeline.latenciesMicros(listOf(1_500_000L to shownClientNanos), offset))
    }

    @Test
    fun `percentiles`() {
        assertNull(ShareTimeline.p50p95Ms(emptyList()))
        val ms = (1..100).map { it * 1_000L }
        assertEquals(51 to 95, ShareTimeline.p50p95Ms(ms.shuffled()))
    }
}
