package com.dreamdisplays.media.player

import com.dreamdisplays.media.player.util.MediaBufferEffects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The volume boost's gains (up to 2.0, 400 % in the menu) reach the samples and are limited, never wrapped. */
class VolumeBoostGainTest {
    private fun applied(user: Float): Double {
        var last = -1.0
        VolumeController(0.5) { last = it }.setUserVolume(user)
        return last
    }

    @Test
    fun userGainUpTo2IsPassedOnAndAboveItCapped() {
        assertEquals(0.5, applied(0.5f), 1e-9)
        assertEquals(1.0, applied(1f), 1e-9)
        assertEquals(1.5, applied(1.5f), 1e-6)
        assertEquals(2.0, applied(2f), 1e-9)
        assertEquals(VolumeController.MAX_USER_GAIN, applied(4f), 1e-9)
        assertEquals(0.0, applied(-1f), 1e-9)
    }

    private fun s16(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[i * 2] = (s and 0xFF).toByte(); b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte() }
    }

    private fun samples(b: ByteArray): List<Int> =
        (0 until b.size / 2).map { (b[it * 2 + 1].toInt() shl 8) or (b[it * 2].toInt() and 0xFF) }

    @Test
    fun quietSamplesAreDoubledExactly() {
        val buf = s16(1000, -1000, 5000, -11000)
        MediaBufferEffects.applyVolumeS16LE(buf, buf.size, 2.0)
        assertEquals(listOf(2000, -2000, 10000, -22000), samples(buf))
    }

    @Test
    fun loudSamplesBendTowardFullScaleWithoutWrapping() {
        val input = intArrayOf(32767, -32768, 20000, -20000, 16000)
        val buf = s16(*input)
        MediaBufferEffects.applyVolumeS16LE(buf, buf.size, 2.0)
        val out = samples(buf)
        input.indices.forEach { i ->
            // Same sign, louder than the input (or at full scale), never past it
            assertTrue(out[i] * input[i] > 0, "sample $i wrapped: ${input[i]} -> ${out[i]}")
            assertTrue(kotlin.math.abs(out[i]) >= kotlin.math.abs(input[i]) || kotlin.math.abs(out[i]) >= 32000, "sample $i")
            assertTrue(out[i] in -32768..32767)
        }
        // Monotonic: a louder input never comes out quieter
        assertTrue(out[0] >= out[2] && out[2] >= out[4])
    }
}
