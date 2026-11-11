package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourceAcousticState
import com.dreamdisplays.api.media.audio.SourcePlane
import com.dreamdisplays.media.audio.dsp.Biquad
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Turning the head fast must not click: the spatial parameters (ITD, ILD, head shadow, pan) follow the listener's
 * orientation, which arrives once per 50 ms block, and must move smoothly across each block instead of stepping at
 * its boundary. Compares a rotating listener against a still one on the same input.
 */
class RotationGlitchTest {
    private companion object {
        const val RATE = 44100f
        const val BLOCK = 2205 // AudioSink's 50 ms chunk
        const val WARMUP_BLOCKS = 20
        const val MEASURE_BLOCKS = 20
    }

    private enum class Input { TONE, NOISE }

    private class Metrics(val jumpRatio: Double, val hfExcessDb: Double)

    private val report = StringBuilder()

    /** Yaw (degrees, positive = turned right) per measured block, or one constant for the still reference. */
    private fun interface Motion {
        fun yawAt(block: Int): Double
    }

    private val still = Motion { -90.0 }
    private val snap180 = Motion { b -> if (b < 2) -90.0 else 90.0 }
    private fun sweep(degPerSec: Double) = Motion { b ->
        // Triangle wave between -90 and +90 degrees at [degPerSec], one pose per block (the game-tick listener feed).
        val travel = degPerSec * b * BLOCK / RATE
        val phase = travel % 360.0
        if (phase < 180.0) -90.0 + phase else 270.0 - phase
    }

    @Test
    fun `fast head turns add no clicks in any spatial mode`() {
        val modes = [
            Triple("enhanced binaural", AcousticQuality.ADVANCED, true),
            Triple("enhanced speakers", AcousticQuality.ADVANCED, false),
            Triple("basic", AcousticQuality.BASIC, true),
        ]
        val motions = [
            "180 deg in one block" to snap180,
            "1000 deg/s" to sweep(1000.0),
            "3000 deg/s" to sweep(3000.0),
        ]
        val failures = mutableListOf<String>()
        for ((modeName, tier, binaural) in modes) {
            for (input in Input.entries) {
                val ref = render(tier, binaural, input, still)
                for ((motionName, motion) in motions) {
                    val m = measure(render(tier, binaural, input, motion), ref)
                    val line = "%-18s %-6s %-21s max jump x%.2f of still, HF(>8k) excess %+.1f dB".format(
                        modeName, input, motionName, m.jumpRatio, m.hfExcessDb,
                    )
                    report.appendLine(line)
                    // A click is a sample step far above the still signal's largest step, or a burst of energy
                    // above 8 kHz that the band-limited input does not contain.
                    if (m.jumpRatio > 1.2 || m.hfExcessDb > 1.0) failures += line
                }
            }
        }
        File("build").mkdirs()
        File("build/rotation-glitch.txt").writeText(report.toString())
        println(report)
        assertTrue(failures.isEmpty(), "Rotation clicks:\n" + failures.joinToString("\n"))
    }

    /** Renders [WARMUP_BLOCKS] still blocks, then [MEASURE_BLOCKS] following [motion]; returns the measured part (L, R). */
    private fun render(tier: AcousticQuality, binaural: Boolean, input: Input, motion: Motion): Array<FloatArray> {
        val engine = AcousticsEngine(RATE)
        engine.setGlobalQuality(tier)
        engine.setBinauralOutput(binaural)
        engine.setLoudnessNormalization(false)
        val chain = AudioRenderChain(RATE, engine)
        chain.updateState(state())
        val source = source(input, (WARMUP_BLOCKS + MEASURE_BLOCKS) * BLOCK)
        val outL = FloatArray(MEASURE_BLOCKS * BLOCK)
        val outR = FloatArray(MEASURE_BLOCKS * BLOCK)
        val buf = ByteArray(BLOCK * 4)
        for (b in 0 until WARMUP_BLOCKS + MEASURE_BLOCKS) {
            val measured = b - WARMUP_BLOCKS
            engine.updateListener(pose(if (measured < 0) still.yawAt(0) else motion.yawAt(measured)))
            for (i in 0 until BLOCK) {
                val v = (source[b * BLOCK + i] * 32767f).toInt()
                buf[i * 4] = (v and 0xFF).toByte(); buf[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
                buf[i * 4 + 2] = (v and 0xFF).toByte(); buf[i * 4 + 3] = ((v shr 8) and 0xFF).toByte()
            }
            chain.process(buf, buf.size, 1.0)
            if (measured < 0) continue
            for (i in 0 until BLOCK) {
                outL[measured * BLOCK + i] = ((buf[i * 4 + 1].toInt() shl 8) or (buf[i * 4].toInt() and 0xFF)) / 32768f
                outR[measured * BLOCK + i] = ((buf[i * 4 + 3].toInt() shl 8) or (buf[i * 4 + 2].toInt() and 0xFF)) / 32768f
            }
        }
        return arrayOf(outL, outR)
    }

    /** A 440 Hz tone, or white noise low-passed to ~2 kHz: neither has content above 8 kHz of its own. */
    private fun source(input: Input, n: Int): FloatArray = when (input) {
        Input.TONE -> FloatArray(n) { (0.3 * sin(2 * PI * 440.0 * it / RATE)).toFloat() }
        Input.NOISE -> {
            val rnd = Random(42)
            val lp = listOf(Biquad(), Biquad()).onEach { it.configure(Biquad.Type.LOW_PASS, RATE, 2000f, 0.707f) }
            FloatArray(n) { lp.fold(rnd.nextFloat() * 0.6f - 0.3f) { x, f -> f.process(x) } }
        }
    }

    private fun measure(out: Array<FloatArray>, ref: Array<FloatArray>): Metrics {
        val jump = out.maxOf { maxStep(it) } / ref.maxOf { maxStep(it) }
        val hf = out.sumOf { hfEnergy(it) } / max(ref.sumOf { hfEnergy(it) }, 1e-12)
        return Metrics(jump, 10 * log10(max(hf, 1e-12)))
    }

    private fun maxStep(x: FloatArray): Double {
        var m = 0.0
        for (i in 1 until x.size) m = max(m, abs(x[i] - x[i - 1]).toDouble())
        return m
    }

    /** Energy above ~8 kHz (4th-order high-pass) plus a tiny floor so a perfectly clean reference never divides by 0. */
    private fun hfEnergy(x: FloatArray): Double {
        val hp = listOf(Biquad(), Biquad()).onEach { it.configure(Biquad.Type.HIGH_PASS, RATE, 8000f, 0.707f) }
        var e = 1e-9
        for (s in x) {
            val y = hp.fold(s) { v, f -> f.process(v) }
            e += y.toDouble() * y
        }
        return e
    }

    private fun pose(yawDeg: Double): ListenerPose {
        val yaw = Math.toRadians(yawDeg)
        return ListenerPose(0.0, 0.0, 0.0, sin(yaw), 0.0, -cos(yaw), 0.0, 1.0, 0.0)
    }

    private fun state() = SourceAcousticState(
        plane = SourcePlane(
            centerX = 0.0, centerY = 0.0, centerZ = -5.0,
            normalX = 0.0, normalY = 0.0, normalZ = 1.0,
            uAxisX = 1.0, uAxisY = 0.0, uAxisZ = 0.0,
            vAxisX = 0.0, vAxisY = 1.0, vAxisZ = 0.0,
            width = 4.0, height = 2.0,
        ),
        userVolume = 1.0f,
        muted = false,
        bypassSpatial = false,
    )
}
