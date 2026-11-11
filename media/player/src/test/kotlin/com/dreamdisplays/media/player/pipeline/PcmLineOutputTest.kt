package com.dreamdisplays.media.player.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcmLineOutputTest {
    private val content = PcmLineFormat.CONTENT
    private val chunkBytes = AudioSink.SAMPLE_RATE * 4 / 20

    private fun sine(fromFrame: Long, frames: Int, hz: Double = 440.0): ByteArray {
        val b = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (12_000 * sin(2 * PI * hz * (fromFrame + i) / AudioSink.SAMPLE_RATE)).roundToInt()
            for (c in 0..1) {
                b[i * 4 + c * 2] = v.toByte()
                b[i * 4 + c * 2 + 1] = (v shr 8).toByte()
            }
        }
        return b
    }

    // --- Format choice -------------------------------------------------------------------------------------

    @Test
    fun `fallback list prefers 48 kHz 16-bit stereo and never repeats the content format`() {
        val f = PcmLineFormat.FALLBACKS
        assertEquals(PcmLineFormat(48_000, 16, 2, false), f.first())
        assertEquals(PcmLineFormat(48_000, 24, 2, false), f[1])
        assertFalse(content in f)
        assertEquals(f.size, f.toSet().size)
        assertTrue(PcmLineFormat(192_000, 24, 2, false) in f)
        assertTrue(PcmLineFormat(48_000, 16, 1, false) in f)
        assertTrue(PcmLineFormat(48_000, 16, 2, true) in f)
        // Stereo little-endian comes before any mono or big-endian variant
        assertTrue(f.indexOf(PcmLineFormat(32_000, 32, 2, false)) < f.indexOf(PcmLineFormat(48_000, 16, 2, true)))
        assertTrue(f.indexOf(PcmLineFormat(32_000, 32, 2, true)) < f.indexOf(PcmLineFormat(48_000, 16, 1, false)))
    }

    @Test
    fun `content format on the game device wins when it opens`() {
        val r = PcmLineFormat.choose(listOf("game", "default"), listOf("hdmi")) { d, f -> "$d/$f" }
        assertEquals(Triple("game", content, "game/$content"), r)
    }

    @Test
    fun `content format on the default device before converting on the game device`() {
        val accepts = mapOf("game" to setOf(PcmLineFormat(48_000, 16, 2, false)), "default" to setOf(content))
        val r = PcmLineFormat.choose(listOf("game", "default"), listOf("hdmi")) { d, f ->
            if (f in accepts.getValue(d)) d else null
        }
        assertEquals("default", r?.first)
        assertEquals(content, r?.second)
    }

    @Test
    fun `a device that only takes 192 kHz 24-bit gets a converted line, not another device`() {
        val only = PcmLineFormat(192_000, 24, 2, false)
        val tried = ArrayList<Pair<String, PcmLineFormat>>()
        val r = PcmLineFormat.choose(listOf("umc", "default"), listOf("hdmi")) { d, f ->
            tried += d to f
            if (d == "hdmi" || (d != "hdmi" && f == only)) d else null
        }
        assertEquals("umc", r?.first)
        assertEquals(only, r?.second)
        assertFalse(tried.any { it.first == "hdmi" })
    }

    @Test
    fun `other devices are the last resort, content format first`() {
        val r = PcmLineFormat.choose(listOf("game", "default"), listOf("hdmi")) { d, f ->
            if (d == "hdmi") d else null
        }
        assertEquals(Triple("hdmi", content, "hdmi"), r)
        assertNull(PcmLineFormat.choose(listOf("a"), listOf("b")) { _, _ -> null })
    }

    @Test
    fun `advertised formats expand unspecified rates and channels and skip what we cannot produce`() {
        val ns = javax.sound.sampled.AudioSystem.NOT_SPECIFIED
        val any = javax.sound.sampled.AudioFormat(
            javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, ns.toFloat(), 24, ns, ns, ns.toFloat(), false,
        )
        val expanded = PcmLineFormat.fromAdvertised(any)
        assertEquals(PcmLineFormat.STANDARD_RATES.size * 2, expanded.size)
        assertTrue(PcmLineFormat(192_000, 24, 1, false) in expanded)
        val float48 = javax.sound.sampled.AudioFormat(
            javax.sound.sampled.AudioFormat.Encoding.PCM_FLOAT, 48_000f, 32, 2, 8, 48_000f, false,
        )
        assertEquals(listOf(PcmLineFormat(48_000, 32, 2, false, float = true)), PcmLineFormat.fromAdvertised(float48))
        val ulaw = javax.sound.sampled.AudioFormat(
            javax.sound.sampled.AudioFormat.Encoding.ULAW, 8_000f, 8, 1, 1, 8_000f, false,
        )
        assertTrue(PcmLineFormat.fromAdvertised(ulaw).isEmpty())
        val eightBit = javax.sound.sampled.AudioFormat(44_100f, 8, 2, true, false)
        assertTrue(PcmLineFormat.fromAdvertised(eightBit).isEmpty())
        val sixChannel = javax.sound.sampled.AudioFormat(48_000f, 16, 6, true, false)
        assertTrue(PcmLineFormat.fromAdvertised(sixChannel).isEmpty())
    }

    @Test
    fun `advertised formats are ordered stereo, nearest standard rate, smallest depth first`() {
        val ordered = PcmLineFormat.preferenceOrder(
            listOf(
                PcmLineFormat(192_000, 24, 2, false), PcmLineFormat(48_000, 32, 2, false, float = true),
                PcmLineFormat(48_000, 24, 1, false), PcmLineFormat(48_000, 24, 2, false),
                PcmLineFormat(48_000, 24, 2, false),
            ),
        )
        assertEquals(
            listOf(
                PcmLineFormat(48_000, 24, 2, false), PcmLineFormat(48_000, 32, 2, false, float = true),
                PcmLineFormat(192_000, 24, 2, false), PcmLineFormat(48_000, 24, 1, false),
            ),
            ordered,
        )
    }

    @Test
    fun `what a device advertises is tried before the generic fallbacks`() {
        val advertised = PcmLineFormat(192_000, 24, 2, false)
        val tried = ArrayList<PcmLineFormat>()
        val r = PcmLineFormat.choose(listOf("umc"), emptyList(), { listOf(advertised) }) { _, f ->
            tried += f
            if (f == advertised || f == PcmLineFormat(48_000, 16, 2, false)) "line" else null
        }
        assertEquals(advertised, r?.second)
        assertEquals(listOf(content, advertised), tried)
    }

    // --- Conversion and clock mapping ---------------------------------------------------------------------

    @Test
    fun `content format at rate 1 is a byte-exact copy with an identity clock map`() {
        val out = PcmLineOutput(content)
        var fed = 0L
        repeat(40) {
            val input = sine(fed, chunkBytes / 4)
            fed += chunkBytes / 4
            val n = out.convert(input, input.size, 1.0)
            assertEquals(input.size, n)
            assertContentEquals(input, out.output.copyOf(n))
        }
        assertEquals(fed, out.outFramesWritten)
        assertEquals(12_345L, out.frameMap.contentAt(12_345L))
    }

    private fun runSeconds(format: PcmLineFormat, seconds: Int, rate: Double = 1.0): PcmLineOutput {
        val out = PcmLineOutput(format)
        var fed = 0L
        repeat(seconds * 20) {
            val input = sine(fed, chunkBytes / 4)
            fed += chunkBytes / 4
            out.convert(input, input.size, rate)
        }
        return out
    }

    @Test
    fun `10 s of content through a 48 kHz line reports 10 s of content time`() {
        val format = PcmLineFormat(48_000, 16, 2, false)
        val out = runSeconds(format, 10)
        // About 10 s of device frames were produced (a few held back for interpolation)
        assertTrue(abs(out.outFramesWritten - 480_000) <= 8, "out frames ${out.outFramesWritten}")
        val contentFrames = out.frameMap.contentAt(out.outFramesWritten)
        val contentNanos = AudioSink.framesToNanos(contentFrames)
        assertTrue(abs(contentNanos - 10_000_000_000L) < 1_000_000L, "content $contentNanos ns")
        // Halfway through the line is halfway through the content
        val half = AudioSink.framesToNanos(out.frameMap.contentAt(240_000L))
        assertTrue(abs(half - 5_000_000_000L) < 1_000_000L, "half $half ns")
    }

    @Test
    fun `sync rate composes with the device rate`() {
        val out = runSeconds(PcmLineFormat(96_000, 24, 2, false), 10, rate = 1.01)
        // 1 % fast: 10 s of content plays in about 9.9 s of device time
        assertTrue(abs(out.outFramesWritten - 950_495) <= 16, "out frames ${out.outFramesWritten}")
        val content = AudioSink.framesToNanos(out.frameMap.contentAt(out.outFramesWritten))
        assertTrue(abs(content - 10_000_000_000L) < 1_000_000L, "content $content ns")
    }

    @Test
    fun `48 kHz output is still the same tone`() {
        val format = PcmLineFormat(48_000, 16, 2, false)
        val out = PcmLineOutput(format)
        var fed = 0L
        var maxErr = 0.0
        repeat(20) {
            val input = sine(fed, chunkBytes / 4)
            fed += chunkBytes / 4
            val startOut = out.outFramesWritten
            val n = out.convert(input, input.size, 1.0) / 4
            for (k in 0 until n) {
                val i = k * 4
                val s = ((out.output[i].toInt() and 0xFF) or (out.output[i + 1].toInt() shl 8)).toShort()
                val t = (startOut + k) * (44_100.0 / 48_000.0)
                val expected = 12_000 * sin(2 * PI * 440.0 * t / 44_100.0)
                maxErr = maxOf(maxErr, abs(s - expected))
            }
        }
        assertTrue(maxErr < 30.0, "max error $maxErr")
    }

    // --- Packing -------------------------------------------------------------------------------------------

    private fun frame(l: Int, r: Int) = byteArrayOf(l.toByte(), (l shr 8).toByte(), r.toByte(), (r shr 8).toByte())

    @Test
    fun `packs 24 and 32 bit, both byte orders`() {
        val src = frame(0x1234, -2) // right = 0xFFFE
        val d24 = ByteArray(6)
        PcmPacker.pack(src, 1, d24, PcmLineFormat(48_000, 24, 2, false))
        assertContentEquals(byteArrayOf(0x00, 0x34, 0x12, 0x00, 0xFE.toByte(), 0xFF.toByte()), d24)
        val d24be = ByteArray(6)
        PcmPacker.pack(src, 1, d24be, PcmLineFormat(48_000, 24, 2, true))
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x00, 0xFF.toByte(), 0xFE.toByte(), 0x00), d24be)
        val d32 = ByteArray(8)
        PcmPacker.pack(src, 1, d32, PcmLineFormat(48_000, 32, 2, false))
        assertContentEquals(
            byteArrayOf(0, 0, 0x34, 0x12, 0, 0, 0xFE.toByte(), 0xFF.toByte()), d32,
        )
        val d16be = ByteArray(4)
        PcmPacker.pack(src, 1, d16be, PcmLineFormat(48_000, 16, 2, true))
        assertContentEquals(byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0xFE.toByte()), d16be)
    }

    @Test
    fun `full scale stays full scale and mono averages the channels`() {
        val src = frame(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()) + frame(1000, 3000)
        val d32 = ByteArray(16)
        PcmPacker.pack(src, 2, d32, PcmLineFormat(48_000, 32, 2, false))
        fun int32(at: Int) = (d32[at].toInt() and 0xFF) or ((d32[at + 1].toInt() and 0xFF) shl 8) or
                ((d32[at + 2].toInt() and 0xFF) shl 16) or (d32[at + 3].toInt() shl 24)
        assertEquals(Short.MAX_VALUE.toInt() shl 16, int32(0))
        assertEquals(Int.MIN_VALUE, int32(4))
        val mono = ByteArray(4)
        PcmPacker.pack(src, 2, mono, PcmLineFormat(48_000, 16, 1, false))
        fun s16(at: Int) = ((mono[at].toInt() and 0xFF) or (mono[at + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(-1, s16(0)) // (32767 + -32768) / 2, rounded down
        assertEquals(2000, s16(2))
    }

    @Test
    fun `packs 32-bit float`() {
        val src = frame(16_384, -32_768)
        val d = ByteArray(8)
        PcmPacker.pack(src, 1, d, PcmLineFormat(48_000, 32, 2, false, float = true))
        val bb = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.5f, bb.getFloat(0))
        assertEquals(-1f, bb.getFloat(4))
        val be = ByteArray(8)
        PcmPacker.pack(src, 1, be, PcmLineFormat(48_000, 32, 2, true, float = true))
        assertEquals(0.5f, java.nio.ByteBuffer.wrap(be).getFloat(0))
        val mono = ByteArray(4)
        PcmPacker.pack(src, 1, mono, PcmLineFormat(48_000, 32, 1, false, float = true))
        assertEquals(-0.25f, java.nio.ByteBuffer.wrap(mono).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(0))
    }

    @Test
    fun `float output through the converter keeps content time`() {
        val out = runSeconds(PcmLineFormat(48_000, 32, 2, false, float = true), 4)
        val content = AudioSink.framesToNanos(out.frameMap.contentAt(out.outFramesWritten))
        assertTrue(abs(content - 4_000_000_000L) < 1_000_000L, "content $content ns")
    }

    @Test
    fun `format arithmetic`() {
        val f = PcmLineFormat(192_000, 24, 2, false)
        assertEquals(6, f.bytesPerFrame)
        assertEquals(1_152_000, f.bytesPerSecond)
        assertEquals(44_100.0 / 192_000.0, f.baseRatio)
        assertEquals(1.0, content.baseRatio)
        assertTrue(content.isContent)
        assertFalse(PcmLineFormat(44_100, 24, 2, false).isContent)
        val af = f.toAudioFormat()
        assertEquals(192_000f, af.sampleRate)
        assertEquals(24, af.sampleSizeInBits)
        assertEquals(6, af.frameSize)
    }
}
