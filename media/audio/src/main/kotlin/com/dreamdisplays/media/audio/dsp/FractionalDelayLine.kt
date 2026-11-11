package com.dreamdisplays.media.audio.dsp

import kotlin.math.floor

/** Linear-interpolated delay line for sub-sample ITD delays. [capacity] must exceed the largest delay ever requested. */
class FractionalDelayLine(private val capacity: Int = 128) {
    private val buf = FloatArray(capacity)
    private var writeIdx = 0

    /** Pushes the newest sample. */
    fun push(x: Float) {
        buf[writeIdx] = x
        writeIdx = (writeIdx + 1) % capacity
    }

    /** Returns the sample [delaySamples] behind the most recently pushed one (0 = most recent), interpolated. */
    fun read(delaySamples: Float): Float {
        val d = if (delaySamples.isNaN()) 0f else delaySamples.coerceIn(0f, (capacity - 2).toFloat())
        // Index arithmetic stays in integers: wrapping a float position (writeIdx - 1 - d) lands on exactly
        // `capacity` when it is a hair below 0 (64 - 1e-6 rounds to 64f), which indexed past the buffer.
        val whole = floor(d).toInt()
        val frac = d - whole
        val newer = Math.floorMod(writeIdx - 1 - whole, capacity)
        val older = if (newer == 0) capacity - 1 else newer - 1
        return buf[newer] * (1f - frac) + buf[older] * frac
    }

    /** Clears the buffer (call on session reset so a stale tail never bleeds into fresh audio). */
    fun reset() {
        buf.fill(0f)
        writeIdx = 0
    }
}
