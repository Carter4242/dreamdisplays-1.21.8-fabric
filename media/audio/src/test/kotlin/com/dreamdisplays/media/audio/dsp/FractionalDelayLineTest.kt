package com.dreamdisplays.media.audio.dsp

import kotlin.test.Test
import kotlin.test.assertEquals

class FractionalDelayLineTest {
    @Test
    fun `a delay a hair past a whole sample never indexes past the buffer`() {
        // The binaural ITD tap is ~1e-7 samples for a source almost dead ahead; at writeIdx 1 the old float wrap
        // rounded to exactly the capacity and threw "Index 64 out of bounds for length 64".
        for (pushed in 0 until 200) {
            val line = FractionalDelayLine(64)
            repeat(pushed) { line.push(it.toFloat()) }
            for (whole in 0..62) for (eps in floatArrayOf(0f, 1e-7f, 4.8e-7f, 3e-6f, 1e-5f)) {
                line.read(whole + eps) // must not throw
            }
            line.read(Float.NaN)
            line.read(Float.POSITIVE_INFINITY)
            line.read(-1f)
        }
    }

    @Test
    fun `reads interpolate between the two neighbouring samples`() {
        val line = FractionalDelayLine(8)
        repeat(11) { line.push(it.toFloat()) } // newest = 10, wraps the buffer once
        assertEquals(10f, line.read(0f))
        assertEquals(7f, line.read(3f))
        assertEquals(9.75f, line.read(0.25f), 1e-5f)
        assertEquals(4.5f, line.read(5.5f), 1e-5f)
        assertEquals(4f, line.read(100f)) // clamped to capacity - 2
    }
}
