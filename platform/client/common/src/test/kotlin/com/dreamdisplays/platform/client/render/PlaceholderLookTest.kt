package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.displays.DisplayFace
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceholderLookTest {
    private val times = (0L..4_000_000_000L step 37_000_000L).toList()

    @Test
    fun anIdleDisplayShowsTheBarFrozenWithTheSegmentCentred() {
        val first = assertNotNull(PlaceholderLook.segment(DisplayFace.IDLE, 0L))
        assertEquals(0.5f, (first[0] + first[1]) / 2f, 1e-6f)
        assertEquals(PlaceholderLook.SEGMENT_WIDTH, first[1] - first[0], 1e-6f)
        for (t in times) {
            assertContentEquals(first, PlaceholderLook.segment(DisplayFace.IDLE, t))
            assertEquals(PlaceholderLook.IDLE_SHADE, PlaceholderLook.backdropShade(DisplayFace.IDLE, t))
        }
    }

    @Test
    fun aLoadingDisplayAnimatesAsBefore() {
        val starts = times.mapNotNull { PlaceholderLook.segment(DisplayFace.LOADING, it)?.get(0) }.toSet()
        assertTrue(starts.size > 5, "the segment sweeps")
        val shades = times.map { PlaceholderLook.backdropShade(DisplayFace.LOADING, it) }.toSet()
        assertTrue(shades.size > 2, "the backdrop breathes")
        assertTrue(shades.all { it in 8..14 })
        for (t in times) {
            val seg = PlaceholderLook.segment(DisplayFace.LOADING, t) ?: continue
            assertTrue(seg[0] >= PlaceholderLook.TRACK_X0 && seg[1] <= PlaceholderLook.TRACK_X1 && seg[1] > seg[0])
        }
    }

    @Test
    fun anErrorHasNoSegment() {
        for (t in times) assertNull(PlaceholderLook.segment(DisplayFace.ERROR, t))
    }
}
