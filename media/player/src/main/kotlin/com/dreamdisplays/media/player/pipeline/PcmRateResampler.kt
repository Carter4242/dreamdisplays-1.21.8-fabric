package com.dreamdisplays.media.player.pipeline

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Plays stereo 16-bit PCM slightly faster or slower ([process]'s `ratio` = content frames per output frame) by
 * resampling with 4-point Catmull-Rom interpolation, used to nudge playback onto a shared timeline.
 *
 * At ratio 1 it is a byte-exact pass-through. Entering and leaving resampling happens on whole input frames
 * (when it is asked to stop, it runs a hair fast until the read position lands exactly on a frame), so neither
 * transition jumps the waveform. [contentPosition] always says which content frame the next output frame
 * starts at, which lets the audio clock report content time rather than output time.
 *
 * Not thread-safe; one instance per PCM pump.
 */
internal class PcmRateResampler {
    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4

        /** Extra speed used while steering the read position onto a whole frame before passing through again. */
        const val SETTLE_RATE = 0.001
    }

    /** Input frames (interleaved floats) not yet fully consumed; [work] index 0 is input frame [workBase]. */
    private var work = FloatArray(0)
    private var workFrames = 0
    private var workBase = 0L
    private var pos = 0.0

    /** Total input frames handed to [process]. */
    var inputFrames = 0L
        private set

    private var active = false
    private var lastL = 0f
    private var lastR = 0f
    private var haveLast = false

    /** The content frame (in input frames since [reset]) the next output frame starts at. */
    val contentPosition: Double get() = if (active) workBase + pos else inputFrames.toDouble()

    /** True while resampling (or steering back to pass-through). */
    val isActive: Boolean get() = active

    /** Starts over at content frame [startFrames] with no history. */
    fun reset(startFrames: Long = 0L) {
        workFrames = 0
        workBase = startFrames
        pos = 0.0
        inputFrames = startFrames
        active = false
        haveLast = false
    }

    /**
     * Drops buffered input after a discontinuity (skipped audio): the next output starts at the next fed frame.
     * The few buffered frames are lost, which is inaudible next to the skip that caused this.
     */
    fun discontinuity() {
        active = false
        workFrames = 0
        pos = 0.0
        haveLast = false
    }

    /** Largest output size [process] can produce for [inputBytes] of input, in bytes. */
    fun maxOutputBytes(inputBytes: Int): Int = ((inputBytes / BYTES_PER_FRAME + 8) * 1.06).toInt() * BYTES_PER_FRAME + 64

    /**
     * Converts [n] bytes of [input] at [ratio] into [out] and returns the number of output bytes. With ratio 1
     * and no resampling in progress it copies the input unchanged.
     */
    fun process(input: ByteArray, n: Int, ratio: Double, out: ByteArray): Int {
        val frames = n / BYTES_PER_FRAME
        if (!active && ratio == 1.0) {
            System.arraycopy(input, 0, out, 0, n)
            inputFrames += frames
            if (frames > 0) rememberLast(input, frames - 1)
            return n
        }
        if (!active) begin()
        append(input, frames)
        var outFrames = 0
        val settling = ratio == 1.0
        val step = if (settling) 1.0 + SETTLE_RATE else ratio
        while (active) {
            val i = floor(pos).toInt()
            if (i + 2 >= workFrames) break
            writeInterpolated(i, pos - i, out, outFrames++)
            val next = pos + step
            if (settling && floor(next).toInt() > i + 1) {
                // Crossed an extra frame boundary: land exactly on it and go back to pass-through
                pos = (i + 2).toDouble()
                finish(out, outFrames).also { outFrames = it }
            } else {
                pos = next
            }
        }
        if (active) compact()
        return outFrames * BYTES_PER_FRAME
    }

    private fun begin() {
        active = true
        workFrames = 0
        ensureCapacity(1)
        // One frame of history so the first interpolated frame (at a whole position) equals the input
        work[0] = if (haveLast) lastL else 0f
        work[1] = if (haveLast) lastR else 0f
        workFrames = 1
        workBase = inputFrames - 1
        pos = 1.0
        if (!haveLast) {
            // No history: the first appended frame is duplicated into slot 0 once it arrives
            needsHistoryFill = true
        }
    }

    private var needsHistoryFill = false

    private fun append(input: ByteArray, frames: Int) {
        ensureCapacity(workFrames + frames)
        var b = 0
        var w = workFrames * CHANNELS
        repeat(frames) {
            work[w++] = sample(input, b); b += 2
            work[w++] = sample(input, b); b += 2
        }
        if (needsHistoryFill && frames > 0) {
            work[0] = work[CHANNELS]
            work[1] = work[CHANNELS + 1]
            needsHistoryFill = false
        }
        workFrames += frames
        inputFrames += frames
        if (frames > 0) rememberLast(input, frames - 1)
    }

    /** Writes the frames left in [work] from the (whole) position on unchanged and returns to pass-through. */
    private fun finish(out: ByteArray, outFramesSoFar: Int): Int {
        var o = outFramesSoFar
        var i = pos.toInt()
        while (i < workFrames) {
            writeFrame(out, o++, work[i * CHANNELS], work[i * CHANNELS + 1])
            i++
        }
        active = false
        workFrames = 0
        pos = 0.0
        return o
    }

    private fun compact() {
        val drop = (floor(pos).toInt() - 1).coerceAtLeast(0)
        if (drop == 0) return
        System.arraycopy(work, drop * CHANNELS, work, 0, (workFrames - drop) * CHANNELS)
        workFrames -= drop
        workBase += drop
        pos -= drop
    }

    private fun writeInterpolated(i: Int, f: Double, out: ByteArray, frame: Int) {
        val base = (i - 1) * CHANNELS
        val l = catmullRom(work[base], work[base + 2], work[base + 4], work[base + 6], f)
        val r = catmullRom(work[base + 1], work[base + 3], work[base + 5], work[base + 7], f)
        writeFrame(out, frame, l, r)
    }

    private fun catmullRom(x0: Float, x1: Float, x2: Float, x3: Float, f: Double): Double =
        x1 + 0.5 * f * (x2 - x0 + f * (2.0 * x0 - 5.0 * x1 + 4.0 * x2 - x3 + f * (3.0 * (x1 - x2) + x3 - x0)))

    private fun writeFrame(out: ByteArray, frame: Int, l: Double, r: Double) {
        val o = frame * BYTES_PER_FRAME
        putSample(out, o, l)
        putSample(out, o + 2, r)
    }

    private fun writeFrame(out: ByteArray, frame: Int, l: Float, r: Float) = writeFrame(out, frame, l.toDouble(), r.toDouble())

    private fun putSample(out: ByteArray, at: Int, v: Double) {
        val s = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out[at] = s.toByte()
        out[at + 1] = (s shr 8).toByte()
    }

    private fun sample(b: ByteArray, at: Int): Float =
        ((b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8)).toShort().toFloat()

    private fun rememberLast(input: ByteArray, frame: Int) {
        lastL = sample(input, frame * BYTES_PER_FRAME)
        lastR = sample(input, frame * BYTES_PER_FRAME + 2)
        haveLast = true
    }

    private fun ensureCapacity(frames: Int) {
        if (work.size >= frames * CHANNELS) return
        work = work.copyOf(maxOf(frames * CHANNELS, work.size * 2, 4096))
    }
}
