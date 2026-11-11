package com.dreamdisplays.media.audio.spatial

import com.dreamdisplays.media.audio.dsp.FractionalDelayLine
import com.dreamdisplays.media.audio.dsp.LinearRamp
import com.dreamdisplays.media.audio.dsp.SvfLowPass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders one mono emitter into a stereo binaural pair using a parametric ITD (Woodworth's formula)
 * + ILD (broadband gain plus a contralateral low-pass for the head-shadow effect) model. A separate
 * instance is needed per emitter so each keeps its own delay line / filter state.
 *
 * Every parameter is a continuous function of the lateral angle, which moves per sample (see [rampTo]): each ear has
 * its own delay tap, gain and head-shadow filter, so a source crossing the median plane hands over smoothly instead of
 * swapping the near and far ear, and a fast head turn sweeps the ITD tap instead of jumping it.
 */
class ParametricBinaural(private val sampleRate: Float) {
    private companion object {
        const val HEAD_RADIUS_M = 0.0875
        const val SPEED_OF_SOUND = 343.0
        const val MAX_ILD_ATTEN = 0.35f
        const val OPEN_CUTOFF_HZ = 9000f
        const val SHADOWED_CUTOFF_HZ = 2200f
        const val HALF_PI = (PI / 2.0).toFloat()

        /** Shadow amount (sine of the lateral angle) over which the far ear's head-shadow filter fades in (~11.5°). */
        const val SHADOW_FADE_IN = 0.2f

        /** Samples between head-shadow cutoff updates while the angle moves (~0.36 ms at 44.1 kHz). */
        const val CONTROL_INTERVAL = 16
    }

    private val itdScale = (HEAD_RADIUS_M / SPEED_OF_SOUND * sampleRate).toFloat()
    private val delayLine = FractionalDelayLine(64)
    private val shadowL = SvfLowPass()
    private val shadowR = SvfLowPass()
    private val lateral = LinearRamp(0f)
    private var controlCountdown = 0
    var lastL = 0f
    var lastR = 0f

    init {
        updateFilters(0f)
    }

    /**
     * Moves the lateral angle ([lateralRad], 0 = median plane, positive = to the listener's right, ±π/2 = fully to
     * one side) to its new value linearly over the next [samples] calls to [renderSample]. Call once per block.
     */
    fun rampTo(lateralRad: Float, samples: Int) {
        lateral.rampTo(lateralRad.coerceIn(-HALF_PI, HALF_PI), samples)
    }

    /** Renders one mono [sample] into a binaural pair, storing result in [lastL] and [lastR]. */
    fun renderSample(sample: Float) {
        val moving = lateral.moving
        val theta = lateral.next()
        if (moving && (--controlCountdown <= 0 || !lateral.moving)) { // Also lands exactly on the ramp's end
            controlCountdown = CONTROL_INTERVAL
            updateFilters(theta)
        }
        delayLine.push(sample)
        val s = sin(theta)
        // Woodworth ITD on the far ear only; it is 0 on the median plane, so the tap that moves is always continuous.
        val itd = itdScale * (abs(theta) + abs(s))
        lastL = ear(shadowL, delayLine.read(if (theta > 0f) itd else 0f), max(0f, s))
        lastR = ear(shadowR, delayLine.read(if (theta < 0f) itd else 0f), max(0f, -s))
    }

    /** One ear: broadband ILD gain plus a head-shadow low-pass faded in with the shadow amount [shadow] (0..1). */
    private fun ear(filter: SvfLowPass, dry: Float, shadow: Float): Float {
        val shadowed = filter.process(dry) // Always run, so the state is warm when the ear moves into shadow
        val wet = min(1f, shadow / SHADOW_FADE_IN)
        return (dry + (shadowed - dry) * wet) * (1f - MAX_ILD_ATTEN * shadow)
    }

    private fun updateFilters(theta: Float) {
        val s = sin(theta)
        shadowL.setCutoff(sampleRate, cutoffFor(max(0f, s)), 0.7f)
        shadowR.setCutoff(sampleRate, cutoffFor(max(0f, -s)), 0.7f)
    }

    private fun cutoffFor(shadow: Float): Float = OPEN_CUTOFF_HZ - (OPEN_CUTOFF_HZ - SHADOWED_CUTOFF_HZ) * shadow

    /** Clears delay-line and filter state and centers the source (call on session reset). */
    fun reset() {
        delayLine.reset()
        shadowL.reset()
        shadowR.reset()
        lateral.snap(0f)
        controlCountdown = 0
        updateFilters(0f)
    }
}
