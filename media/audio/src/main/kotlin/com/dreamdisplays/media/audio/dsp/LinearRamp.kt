package com.dreamdisplays.media.audio.dsp

/**
 * A control value that moves linearly to each new target over a given number of samples, so a parameter computed once
 * per block reaches the DSP as a continuous per-sample ramp rather than a step at the block boundary.
 */
class LinearRamp(initial: Float = 0f) {
    /** Current per-sample value. */
    var value: Float = initial
        private set
    private var target = initial
    private var step = 0f
    private var remaining = 0

    /** True while the value is still moving toward its target. */
    val moving: Boolean get() = remaining > 0

    /** Starts a ramp from the current value to [newTarget] over [samples] calls to [next]. */
    fun rampTo(newTarget: Float, samples: Int) {
        target = newTarget
        if (samples <= 0 || newTarget == value) {
            value = newTarget; remaining = 0; step = 0f
            return
        }
        step = (newTarget - value) / samples
        remaining = samples
    }

    /** Advances one sample and returns the new value. */
    fun next(): Float {
        if (remaining > 0) {
            remaining--
            value = if (remaining == 0) target else value + step
        }
        return value
    }

    /** Jumps directly to [v] with no ramp (session reset only). */
    fun snap(v: Float) {
        value = v; target = v; step = 0f; remaining = 0
    }
}
