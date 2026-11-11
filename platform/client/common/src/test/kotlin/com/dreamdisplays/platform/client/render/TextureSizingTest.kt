package com.dreamdisplays.platform.client.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextureSizingTest {
    @Test
    fun ordinaryDisplayKeepsItsSize() {
        assertEquals(1920 to 1080, TextureSizing.dimensions(16, 9, 1080))
        assertEquals(2880 to 2160, TextureSizing.dimensions(32, 24, 2160))
    }

    @Test
    fun wideStripIsCappedKeepingAspect() {
        // 32x1 at 1080p was 34560 px wide: past every GPU's texture limit
        val (w, h) = TextureSizing.dimensions(32, 1, 1080)
        assertEquals(TextureSizing.MAX_SIDE, w)
        assertEquals(256, h)
    }

    @Test
    fun tallStripIsCappedOnHeight() {
        val (w, h) = TextureSizing.dimensions(1, 24, 4320, maxSide = 2048)
        assertEquals(2048, h)
        assertEquals(84, w)
    }

    @Test
    fun dimensionsStayEvenAndPositive() {
        for (bw in 1..32) for (bh in 1..24) for (q in listOf(144, 360, 721, 1080, 4320)) {
            val (w, h) = TextureSizing.dimensions(bw, bh, q)
            assertTrue(w >= 2 && h >= 2 && w % 2 == 0 && h % 2 == 0, "$bw x $bh @ $q -> $w x $h")
            assertTrue(w <= TextureSizing.MAX_SIDE && h <= TextureSizing.MAX_SIDE, "$bw x $bh @ $q -> $w x $h")
        }
    }
}
