package com.dreamdisplays.media.audio.spatial

import com.dreamdisplays.media.audio.dsp.LinearRamp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Constant-power stereo pan used for the "speakers" output profile (no binaural processing). One
 * instance is needed per emitter (mirroring [ParametricBinaural]) since [lastL] / [lastR] are shared
 * mutable state. The pan gains move linearly per sample between block updates (see [rampTo]).
 */
class StereoPanner {
    private companion object {
        const val HALF_PI = (PI / 2.0)
    }

    private val gainL = LinearRamp(cos(HALF_PI / 2).toFloat())
    private val gainR = LinearRamp(sin(HALF_PI / 2).toFloat())
    var lastL = 0f
    var lastR = 0f

    /** Moves the pan to [lateralRad] (0 = center, positive = right, ±π/2 = hard) over the next [samples] calls to [pan]. */
    fun rampTo(lateralRad: Double, samples: Int) {
        val t = (lateralRad.coerceIn(-HALF_PI, HALF_PI) / HALF_PI + 1.0) / 2.0 // 0 = left ..1 = right
        val angle = t * HALF_PI
        gainL.rampTo(cos(angle).toFloat(), samples)
        gainR.rampTo(sin(angle).toFloat(), samples)
    }

    /** Pans a mono [sample] to `[left, right]`, storing result in [lastL] and [lastR]. */
    fun pan(sample: Float) {
        lastL = sample * gainL.next()
        lastR = sample * gainR.next()
    }

    /** Centers the pan with no ramp (call on session reset). */
    fun reset() {
        gainL.snap(cos(HALF_PI / 2).toFloat())
        gainR.snap(sin(HALF_PI / 2).toFloat())
    }
}
