package com.dreamdisplays.media.audio.dsp

import kotlin.math.PI
import kotlin.math.tan

/**
 * Second-order low-pass as a trapezoidal (TPT) state-variable filter (Simper's form). Same response as the RBJ
 * low-pass [Biquad], but its state stays valid when the cutoff moves while audio runs, so a swept cutoff never clicks.
 */
class SvfLowPass {
    private var a1 = 1f
    private var a2 = 0f
    private var a3 = 0f
    private var ic1 = 0f
    private var ic2 = 0f

    /** Sets the cutoff to [freqHz] at [sampleRate] with resonance [q]; safe to call between any two samples. */
    fun setCutoff(sampleRate: Float, freqHz: Float, q: Float = 0.707f) {
        val g = tan(PI * (freqHz / sampleRate).coerceIn(0.0001f, 0.499f)).toFloat()
        val k = 1f / q
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    /** Filters one sample, updating internal state. */
    fun process(x: Float): Float {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        return v2
    }

    /** Clears filter history (session reset only — never on a parameter change). */
    fun reset() {
        ic1 = 0f; ic2 = 0f
    }
}
