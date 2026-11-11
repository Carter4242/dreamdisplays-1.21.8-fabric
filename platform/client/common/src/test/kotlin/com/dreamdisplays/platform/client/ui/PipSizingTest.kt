package com.dreamdisplays.platform.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipSizingTest {
    @Test
    fun landscapeKeepsTheChosenWidth() {
        assertEquals(211 to 118, PipSizing.fit(640, 360, 0.33f, 16.0 / 9.0, 12))
    }

    @Test
    fun portraitFitsTheViewportHeight() {
        // 1080p at GUI scale 3: a 9:16 PiP was 211 x 375, taller than the 360 px screen
        val (w, h) = PipSizing.fit(640, 360, 0.33f, 9.0 / 16.0, 12)
        assertEquals(336, h)
        assertEquals(189, w)
        assertTrue(h + 2 * 12 <= 360)
    }

    @Test
    fun largestSizeStillFits() {
        val (_, h) = PipSizing.fit(960, 540, 0.6f, 9.0 / 16.0, 12)
        assertTrue(h <= 540 - 24)
    }

    @Test
    fun unknownAspectFallsBackToWidescreen() {
        assertEquals(PipSizing.fit(640, 360, 0.33f, 16.0 / 9.0, 12), PipSizing.fit(640, 360, 0.33f, 0.0, 12))
    }
}
