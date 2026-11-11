package com.dreamdisplays.media.player.pipeline

/**
 * Maps a line's output frame position to the content frame playing at it, for audio that was written at a
 * playback rate other than 1 (see [PcmRateResampler]).
 *
 * The writer records, per written block, which output frames carry which content span; the clock reader asks
 * what content frame a given output position is at. While everything plays at rate 1 the map is a single
 * identity segment and adds nothing. Thread-safe: one writer (the PCM pump) and any number of readers.
 */
internal class ContentFrameMap(private val capacity: Int = 128) {
    private val outStart = LongArray(capacity)
    private val contentStart = DoubleArray(capacity)
    private val slope = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    init {
        reset()
    }

    /** Back to the identity mapping (output frame N = content frame N). */
    @Synchronized
    fun reset() {
        head = 0
        size = 1
        outStart[0] = 0L
        contentStart[0] = 0.0
        slope[0] = 1.0
    }

    /**
     * Records that output frames `[out, out + outFrames)` carry content frames `[contentFrom, contentTo)`.
     * Blocks that continue the current segment exactly (same rate, no jump) are merged into it.
     */
    @Synchronized
    fun record(out: Long, outFrames: Int, contentFrom: Double, contentTo: Double) {
        if (outFrames <= 0) return
        val s = (contentTo - contentFrom) / outFrames
        val last = (head + size - 1) % capacity
        val predicted = contentStart[last] + (out - outStart[last]) * slope[last]
        if (kotlin.math.abs(predicted - contentFrom) < 1e-6 && kotlin.math.abs(slope[last] - s) < 1e-9) return
        if (size == capacity) {
            head = (head + 1) % capacity
            size--
        }
        val at = (head + size) % capacity
        outStart[at] = out
        contentStart[at] = contentFrom
        slope[at] = s
        size++
    }

    /** The content frame playing at output frame [out]. Older segments the reader has passed are dropped. */
    @Synchronized
    fun contentAt(out: Long): Long {
        // Skip to the newest segment that starts at or before [out]; segments before it can never be read again
        while (size > 1) {
            val next = (head + 1) % capacity
            if (outStart[next] > out) break
            head = next
            size--
        }
        return (contentStart[head] + (out - outStart[head]) * slope[head]).toLong()
    }
}
