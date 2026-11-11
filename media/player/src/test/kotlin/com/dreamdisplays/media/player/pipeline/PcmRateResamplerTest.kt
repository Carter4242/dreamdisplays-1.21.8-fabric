package com.dreamdisplays.media.player.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmRateResamplerTest {
    private val chunkFrames = 2205

    /** Stereo 16-bit sine, left and right in phase, continuing from frame [from]. */
    private fun sine(from: Long, frames: Int, hz: Double = 1_000.0, amp: Double = 12_000.0): ByteArray {
        val b = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (amp * sin(2 * PI * hz * (from + i) / 44_100.0)).roundToInt()
            for (c in 0..1) {
                b[i * 4 + c * 2] = v.toByte()
                b[i * 4 + c * 2 + 1] = (v shr 8).toByte()
            }
        }
        return b
    }

    private fun left(b: ByteArray, frames: Int): IntArray =
        IntArray(frames) { ((b[it * 4].toInt() and 0xFF) or (b[it * 4 + 1].toInt() shl 8)).toShort().toInt() }

    @Test
    fun `rate 1 is a byte-exact pass-through`() {
        val r = PcmRateResampler()
        val out = ByteArray(r.maxOutputBytes(chunkFrames * 4))
        val input = sine(0, chunkFrames)
        val n = r.process(input, input.size, 1.0, out)
        assertEquals(input.size, n)
        assertContentEquals(input, out.copyOf(n))
        assertEquals(chunkFrames.toDouble(), r.contentPosition)
        assertFalse(r.isActive)
    }

    @Test
    fun `faster rate produces proportionally fewer frames and tracks content`() {
        for (ratio in doubleArrayOf(1.01, 0.99, 1.003)) {
            val r = PcmRateResampler()
            val out = ByteArray(r.maxOutputBytes(chunkFrames * 4))
            var outFrames = 0L
            var fed = 0L
            repeat(200) {
                val input = sine(fed, chunkFrames)
                fed += chunkFrames
                outFrames += r.process(input, input.size, ratio, out) / 4
            }
            // Content consumed per output frame equals the ratio (within the few frames held for interpolation)
            assertEquals(ratio, (r.contentPosition) / outFrames, 1e-4, "ratio $ratio")
            assertTrue(fed - r.contentPosition in 0.0..4.0, "held back ${fed - r.contentPosition} frames")
        }
    }

    @Test
    fun `changing rate and returning to 1 is continuous and ends exactly in pass-through`() {
        val r = PcmRateResampler()
        val out = ByteArray(r.maxOutputBytes(chunkFrames * 4))
        val all = ArrayList<Int>()
        var fed = 0L
        val rates = listOf(1.0, 1.0, 1.0005, 1.001, 1.005, 1.01, 1.01, 1.005, 1.0, 1.0, 1.0, 0.995, 0.99, 1.0, 1.0, 1.0)
        for (rate in rates) {
            val input = sine(fed, chunkFrames)
            fed += chunkFrames
            val n = r.process(input, input.size, rate, out)
            all.addAll(left(out, n / 4).toList())
        }
        assertFalse(r.isActive, "should have settled back to pass-through")
        assertEquals(fed.toDouble(), r.contentPosition)
        // A 1 kHz sine at amplitude 12000 changes by at most ~1710 per frame; a click would far exceed that
        var maxStep = 0
        for (i in 1 until all.size) maxStep = maxOf(maxStep, abs(all[i] - all[i - 1]))
        assertTrue(maxStep <= 1_760, "largest sample step $maxStep")
        // Pass-through again: the last chunk is exactly the input
        val last = sine(fed, chunkFrames)
        val n = r.process(last, last.size, 1.0, out)
        assertContentEquals(last, out.copyOf(n))
    }

    @Test
    fun `resampled signal stays the same tone`() {
        val r = PcmRateResampler()
        val out = ByteArray(r.maxOutputBytes(chunkFrames * 4))
        var fed = 0L
        var err = 0.0
        var count = 0
        repeat(20) {
            val input = sine(fed, chunkFrames, hz = 440.0)
            val contentFrom = r.contentPosition
            fed += chunkFrames
            val n = r.process(input, input.size, 1.01, out) / 4
            val samples = left(out, n)
            // Output frame k plays content frame contentFrom + k * 1.01
            for (k in 0 until n) {
                val expected = 12_000.0 * sin(2 * PI * 440.0 * (contentFrom + k * 1.01) / 44_100.0)
                err = maxOf(err, abs(samples[k] - expected))
                count++
            }
        }
        assertTrue(count > 40_000)
        assertTrue(err < 30.0, "max interpolation error $err")
    }

    @Test
    fun `discontinuity drops the held frames and resumes from the next input`() {
        val r = PcmRateResampler()
        val out = ByteArray(r.maxOutputBytes(chunkFrames * 4))
        r.process(sine(0, chunkFrames), chunkFrames * 4, 1.01, out)
        assertTrue(r.isActive)
        r.discontinuity()
        assertFalse(r.isActive)
        assertEquals(chunkFrames.toDouble(), r.contentPosition)
    }

    @Test
    fun `reset starts counting at the given frame`() {
        val r = PcmRateResampler()
        r.reset(1_000)
        assertEquals(1_000.0, r.contentPosition)
        val out = ByteArray(r.maxOutputBytes(400))
        r.process(ByteArray(400), 400, 1.0, out)
        assertEquals(1_100.0, r.contentPosition)
    }
}

class ContentFrameMapTest {
    @Test
    fun `identity until something else is recorded`() {
        val m = ContentFrameMap()
        assertEquals(12_345L, m.contentAt(12_345))
        m.record(0, 1_000, 0.0, 1_000.0)
        m.record(1_000, 1_000, 1_000.0, 2_000.0)
        assertEquals(1_500L, m.contentAt(1_500))
    }

    @Test
    fun `maps output frames through rate segments`() {
        val m = ContentFrameMap()
        m.record(0, 1_000, 0.0, 1_000.0)
        m.record(1_000, 1_000, 1_000.0, 1_010.0 + 1_000.0) // 1 % fast
        m.record(2_000, 1_000, 2_010.0, 3_010.0)
        assertEquals(500L, m.contentAt(500))
        assertEquals(1_505L, m.contentAt(1_500))
        assertEquals(2_510L, m.contentAt(2_500))
    }

    @Test
    fun `reading forward drops passed segments but stays correct`() {
        val m = ContentFrameMap(capacity = 4)
        var out = 0L
        var content = 0.0
        repeat(50) { i ->
            val ratio = if (i % 2 == 0) 1.01 else 0.99
            m.record(out, 100, content, content + 100 * ratio)
            assertEquals((content).toLong(), m.contentAt(out))
            out += 100
            content += 100 * ratio
        }
        assertTrue(abs(content - m.contentAt(out)) <= 1.0)
    }

    @Test
    fun `reset returns to identity`() {
        val m = ContentFrameMap()
        m.record(0, 100, 50.0, 150.0)
        m.reset()
        assertEquals(10L, m.contentAt(10))
    }
}
