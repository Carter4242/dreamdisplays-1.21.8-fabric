package com.dreamdisplays.media.player.pipeline

import javax.sound.sampled.AudioFormat

/**
 * Signed integer PCM format of an opened output line. Content always arrives as [CONTENT] (44.1 kHz, 16-bit,
 * stereo, little-endian); any other format is produced by [PcmLineOutput].
 */
internal data class PcmLineFormat(val sampleRate: Int, val bits: Int, val channels: Int, val bigEndian: Boolean) {
    val bytesPerFrame: Int get() = bits / 8 * channels
    val bytesPerSecond: Int get() = sampleRate * bytesPerFrame

    /** True for the content format itself: no conversion at all. */
    val isContent: Boolean get() = this == CONTENT

    /** Content frames consumed per output frame at normal speed (1.0 at 44.1 kHz, 0.91875 at 48 kHz). */
    val baseRatio: Double get() = AudioSink.SAMPLE_RATE.toDouble() / sampleRate

    fun toAudioFormat(): AudioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, sampleRate.toFloat(), bits, channels, bytesPerFrame,
        sampleRate.toFloat(), bigEndian,
    )

    override fun toString(): String =
        "$sampleRate Hz ${bits}-bit ${if (channels == 1) "mono" else "stereo"}${if (bigEndian) " big-endian" else ""}"

    companion object {
        val CONTENT = PcmLineFormat(AudioSink.SAMPLE_RATE, 16, 2, false)

        /**
         * Formats tried, in order, when a device rejects [CONTENT]: stereo before mono, little- before big-endian,
         * 48 kHz first (what most devices run at), then the other common rates, 16-bit before 24 and 32.
         */
        val FALLBACKS: List<PcmLineFormat> = buildList {
            for (channels in intArrayOf(2, 1)) for (bigEndian in booleanArrayOf(false, true)) {
                for (rate in intArrayOf(48_000, 44_100, 96_000, 88_200, 192_000, 176_400, 32_000)) {
                    for (bits in intArrayOf(16, 24, 32)) {
                        val f = PcmLineFormat(rate, bits, channels, bigEndian)
                        if (f != CONTENT) add(f)
                    }
                }
            }
        }

        /**
         * Picks the line to use: [CONTENT] on the [preferred] devices first (the game's device, then the system
         * default), then a converted format on those same devices, and only then any [other] device. Returns the
         * first candidate [tryOpen] manages to open, or null. [tryOpen] must not throw.
         */
        fun <M, L> choose(
            preferred: List<M>,
            other: List<M>,
            tryOpen: (M, PcmLineFormat) -> L?,
        ): Triple<M, PcmLineFormat, L>? {
            fun attempt(devices: List<M>, formats: List<PcmLineFormat>): Triple<M, PcmLineFormat, L>? {
                for (device in devices) for (format in formats) {
                    tryOpen(device, format)?.let { return Triple(device, format, it) }
                }
                return null
            }
            val content = listOf(CONTENT)
            return attempt(preferred, content) ?: attempt(preferred, FALLBACKS)
            ?: attempt(other, content) ?: attempt(other, FALLBACKS)
        }
    }
}

/**
 * Turns content PCM (44.1 kHz / 16-bit / stereo) into what one output line plays, and records which content
 * frame every output frame carries ([frameMap]) so the audio clock keeps reporting content time whatever the
 * device rate. The sample-rate conversion is the sync [PcmRateResampler] run at `syncRate * baseRatio`; for the
 * content format that is exactly the sync rate, so normal playback stays a byte-exact copy.
 *
 * One instance per line, used only by the thread that writes it.
 */
internal class PcmLineOutput(val format: PcmLineFormat) {
    /** Which content frame each output frame of the line carries. */
    val frameMap = ContentFrameMap()

    /** Output (device) frames produced so far. */
    var outFramesWritten = 0L
        private set

    private val resampler = PcmRateResampler()
    private var stage = ByteArray(0)
    private var packed = ByteArray(0)

    /** Buffer holding the last [convert] result. */
    var output: ByteArray = ByteArray(0)
        private set

    /** Drops the resampler's held frames after content was skipped (see [PcmRateResampler.discontinuity]). */
    fun discontinuity() = resampler.discontinuity()

    /**
     * Converts [n] bytes of content [chunk] at the sync [rate] and returns the byte count now in [output].
     */
    fun convert(chunk: ByteArray, n: Int, rate: Double): Int {
        val ratio = if (format.isContent) rate else rate * format.baseRatio
        val needed = maxOutputBytes(n, ratio)
        if (stage.size < needed) stage = ByteArray(needed)
        val contentFrom = resampler.contentPosition
        val bytes16 = resampler.process(chunk, n, ratio, stage)
        val frames = bytes16 / AudioSink.BYTES_PER_FRAME
        frameMap.record(outFramesWritten, frames, contentFrom, resampler.contentPosition)
        outFramesWritten += frames
        if (format.isContent) {
            output = stage
            return bytes16
        }
        val outBytes = frames * format.bytesPerFrame
        if (packed.size < outBytes) packed = ByteArray(outBytes)
        PcmPacker.pack(stage, frames, packed, format)
        output = packed
        return outBytes
    }

    private fun maxOutputBytes(n: Int, ratio: Double): Int {
        // The resampler slews toward rates within 5 % of the base ratio and holds a few frames back
        val minRatio = (ratio * 0.94).coerceAtMost(0.94)
        return ((n / AudioSink.BYTES_PER_FRAME + 16) / minRatio).toInt() * AudioSink.BYTES_PER_FRAME + 64
    }
}

/** Repacks 16-bit stereo little-endian frames into another [PcmLineFormat]'s bit depth, channels and byte order. */
internal object PcmPacker {
    fun pack(src: ByteArray, frames: Int, dst: ByteArray, format: PcmLineFormat) {
        val bytesPerSample = format.bits / 8
        var o = 0
        for (f in 0 until frames) {
            val i = f * 4
            val l = ((src[i].toInt() and 0xFF) or (src[i + 1].toInt() shl 8)).toShort().toInt()
            val r = ((src[i + 2].toInt() and 0xFF) or (src[i + 3].toInt() shl 8)).toShort().toInt()
            if (format.channels == 1) {
                o = put((l + r) shr 1, dst, o, bytesPerSample, format.bigEndian)
            } else {
                o = put(l, dst, o, bytesPerSample, format.bigEndian)
                o = put(r, dst, o, bytesPerSample, format.bigEndian)
            }
        }
    }

    /** Writes the 16-bit sample [s] as a [bytes]-wide sample (left-justified, so full scale stays full scale). */
    private fun put(s: Int, dst: ByteArray, at: Int, bytes: Int, bigEndian: Boolean): Int {
        val v = s shl ((bytes - 2) * 8)
        for (b in 0 until bytes) {
            val shift = if (bigEndian) (bytes - 1 - b) * 8 else b * 8
            dst[at + b] = (v shr shift).toByte()
        }
        return at + bytes
    }
}
