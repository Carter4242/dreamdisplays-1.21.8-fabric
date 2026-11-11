package com.dreamdisplays.core.karaoke

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Volume for a karaoke voice copy, on decoded 16-bit PCM: plain scaling at or below 1, and above 1 (the
 * `singer_volume` boost) a soft limiter that leaves everything under [KNEE] of full scale exactly scaled and bends
 * louder peaks smoothly (tanh) toward full scale, so boosted loud singing never hard-clips.
 */
object KaraokeVoiceGain {
    /** Fraction of full scale where the limiter starts bending. */
    const val KNEE = 0.7
    private const val FULL = 32_767.0

    /** True when [gain] changes nothing, so the frame can pass through without re-encoding. */
    fun isUnity(gain: Double): Boolean = abs(gain - 1.0) < 1e-6

    /** One sample at [gain]. */
    fun sample(x: Short, gain: Double): Short {
        val y = x * gain
        if (gain <= 1.0) return y.toInt().toShort()
        val knee = KNEE * FULL
        val a = abs(y)
        if (a <= knee) return y.toInt().toShort()
        val over = (a - knee) / (FULL - knee)
        val limited = knee + (FULL - knee) * tanh(over)
        return (if (y < 0) -limited else limited).toInt().coerceIn(-32_768, 32_767).toShort()
    }

    /** [pcm] at [gain], in place. */
    fun apply(pcm: ShortArray, gain: Double) {
        if (isUnity(gain)) return
        for (i in pcm.indices) pcm[i] = sample(pcm[i], gain)
    }
}
