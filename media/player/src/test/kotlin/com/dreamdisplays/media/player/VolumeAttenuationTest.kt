package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeAttenuationTest {
    private fun att(d: Double, max: Double, full: Double = 0.0, squared: Boolean = true) =
        VolumeController.attenuation(d, max, full, squared)

    @Test
    fun defaultIsTheOldQuadraticCurve() {
        assertEquals(1.0, att(0.0, 96.0), 1e-9)
        assertEquals(0.25, att(48.0, 96.0), 1e-9)
        assertEquals(0.0, att(96.0, 96.0), 1e-9)
        assertEquals(0.0, att(200.0, 96.0), 1e-9)
    }

    @Test
    fun linearFalloff() {
        assertEquals(0.5, att(48.0, 96.0, squared = false), 1e-9)
    }

    @Test
    fun fullVolumeZoneThenFade() {
        assertEquals(1.0, att(30.0, 100.0, full = 30.0), 1e-9)
        assertEquals(0.25, att(65.0, 100.0, full = 30.0), 1e-9)
        assertEquals(0.5, att(65.0, 100.0, full = 30.0, squared = false), 1e-9)
        assertEquals(0.0, att(100.0, 100.0, full = 30.0), 1e-9)
    }

    @Test
    fun fullZoneLargerThanRadiusIsSilentBeyondIt() {
        assertEquals(1.0, att(10.0, 20.0, full = 50.0), 1e-9)
        assertEquals(1.0, att(50.0, 20.0, full = 50.0), 1e-9)
        assertEquals(0.0, att(51.0, 20.0, full = 50.0), 1e-9)
    }
}
