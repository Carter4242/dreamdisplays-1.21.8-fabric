package com.dreamdisplays.platform.client.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaceholderLookTest {
    private val times = (0L..4_000_000_000L step 37_000_000L).toList()

    @Test
    fun theRestingLookHasTheSegmentCentredAndTheMidShade() {
        val rest = assertNotNull(PlaceholderLook.segment(0L))
        assertEquals(0.5f, (rest[0] + rest[1]) / 2f, 1e-6f)
        assertEquals(PlaceholderLook.SEGMENT_WIDTH, rest[1] - rest[0], 1e-6f)
        assertEquals(11, PlaceholderLook.IDLE_SHADE)
        // Every rest point of each motion is the same look
        for (k in 1..4) {
            val again = assertNotNull(PlaceholderLook.segment(k * PlaceholderLook.SWEEP_REST_NANOS))
            assertEquals(rest[0], again[0], 1e-6f)
            assertEquals(rest[1], again[1], 1e-6f)
            assertEquals(PlaceholderLook.IDLE_SHADE, PlaceholderLook.backdropShade(k * PlaceholderLook.BREATHE_REST_NANOS))
        }
    }

    @Test
    fun theLoadingLookAnimates() {
        val starts = times.mapNotNull { PlaceholderLook.segment(it)?.get(0) }.toSet()
        assertTrue(starts.size > 5, "the segment sweeps")
        val shades = times.map { PlaceholderLook.backdropShade(it) }.toSet()
        assertTrue(shades.size > 2, "the backdrop breathes")
        assertTrue(shades.all { it in 8..14 })
        for (t in times) {
            val seg = PlaceholderLook.segment(t) ?: continue
            assertTrue(seg[0] >= PlaceholderLook.TRACK_X0 && seg[1] <= PlaceholderLook.TRACK_X1 && seg[1] > seg[0])
        }
    }

    @Test
    fun theSweepMovesRightFromTheMiddle() {
        val rest = assertNotNull(PlaceholderLook.segment(0L))
        val next = assertNotNull(PlaceholderLook.segment(16_000_000L))
        assertTrue(next[0] > rest[0] && next[0] - rest[0] < 0.02f)
    }
}
