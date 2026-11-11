package com.dreamdisplays.media.player.pipeline

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * PCM format of an opened output line: signed integer (16 / 24 / 32-bit) or 32-bit float. Content always arrives as
 * [CONTENT] (44.1 kHz, 16-bit, stereo, little-endian); any other format is produced by [PcmLineOutput].
 */
internal data class PcmLineFormat(
    val sampleRate: Int,
    val bits: Int,
    val channels: Int,
    val bigEndian: Boolean,
    val float: Boolean = false,
) {
    val bytesPerFrame: Int get() = bits / 8 * channels
    val bytesPerSecond: Int get() = sampleRate * bytesPerFrame

    /** True for the content format itself: no conversion at all. */
    val isContent: Boolean get() = this == CONTENT

    /** Content frames consumed per output frame at normal speed (1.0 at 44.1 kHz, 0.91875 at 48 kHz). */
    val baseRatio: Double get() = AudioSink.SAMPLE_RATE.toDouble() / sampleRate

    fun toAudioFormat(): AudioFormat = AudioFormat(
        if (float) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
        sampleRate.toFloat(), bits, channels, bytesPerFrame, sampleRate.toFloat(), bigEndian,
    )

    override fun toString(): String =
        "$sampleRate Hz ${bits}-bit${if (float) " float" else ""} ${if (channels == 1) "mono" else "stereo"}" +
                if (bigEndian) " big-endian" else ""

    companion object {
        val CONTENT = PcmLineFormat(AudioSink.SAMPLE_RATE, 16, 2, false)

        /** Rates tried for a format whose rate a device leaves open, best first. */
        val STANDARD_RATES = intArrayOf(48_000, 44_100, 96_000, 88_200, 192_000, 176_400, 32_000)

        /**
         * Formats tried, in order, when a device rejects [CONTENT] and advertises nothing usable: stereo before mono,
         * little- before big-endian, 48 kHz first (what most devices run at), 16-bit before 24 and 32, then float.
         */
        val FALLBACKS: List<PcmLineFormat> = buildList {
            for (channels in intArrayOf(2, 1)) for (bigEndian in booleanArrayOf(false, true)) {
                for (rate in STANDARD_RATES) {
                    for (bits in intArrayOf(16, 24, 32)) {
                        val f = PcmLineFormat(rate, bits, channels, bigEndian)
                        if (f != CONTENT) add(f)
                    }
                    add(PcmLineFormat(rate, 32, channels, bigEndian, float = true))
                }
            }
        }

        /**
         * What a device's advertised [format] can be opened as: null for encodings / sizes we cannot produce;
         * unspecified rates or channel counts expand to every standard choice. Ordered best first.
         */
        fun fromAdvertised(format: AudioFormat): List<PcmLineFormat> {
            val float = when (format.encoding) {
                AudioFormat.Encoding.PCM_SIGNED -> false
                AudioFormat.Encoding.PCM_FLOAT -> true
                else -> return emptyList()
            }
            val bits = format.sampleSizeInBits
            if (float && bits != 32) return emptyList()
            if (!float && bits != 16 && bits != 24 && bits != 32) return emptyList()
            val channelOptions = when (format.channels) {
                AudioSystem.NOT_SPECIFIED -> intArrayOf(2, 1)
                1, 2 -> intArrayOf(format.channels)
                else -> return emptyList()
            }
            val rate = format.sampleRate
            val rates = if (rate == AudioSystem.NOT_SPECIFIED.toFloat() || rate <= 0f) STANDARD_RATES
            else rate.toInt().takeIf { it in 8_000..384_000 }?.let { intArrayOf(it) } ?: return emptyList()
            // A 16-bit / 8-bit-agnostic device may leave the byte order unspecified; Java Sound reports it as false
            val bigEndian = bits > 8 && format.isBigEndian
            return buildList {
                for (c in channelOptions) for (r in rates) add(PcmLineFormat(r, bits, c, bigEndian, float))
            }
        }

        /**
         * Orders formats a device advertises by preference: stereo, then the rate closest to 44.1 / 48 kHz,
         * 16-bit before 24 / 32 / float, little-endian first. Drops duplicates.
         */
        fun preferenceOrder(formats: List<PcmLineFormat>): List<PcmLineFormat> = formats.distinct().sortedWith(
            compareBy<PcmLineFormat>({ if (it.channels == 2) 0 else 1 }, { rateRank(it.sampleRate) },
                { if (it.float) 99 else it.bits }, { it.bigEndian }),
        )

        private fun rateRank(rate: Int): Int = STANDARD_RATES.indexOf(rate).let { if (it < 0) 100 else it }

        /**
         * Picks the line to use: [CONTENT] on the [preferred] devices first (the game's device, then the system
         * default), then a format those devices advertise ([advertised], best first) or one of the [FALLBACKS],
         * and only then the same on any [other] device. Returns the first candidate [tryOpen] manages to open, or
         * null. [tryOpen] must not throw.
         */
        fun <M, L> choose(
            preferred: List<M>,
            other: List<M>,
            advertised: (M) -> List<PcmLineFormat> = { emptyList() },
            tryOpen: (M, PcmLineFormat) -> L?,
        ): Triple<M, PcmLineFormat, L>? {
            fun attempt(devices: List<M>, formatsFor: (M) -> List<PcmLineFormat>): Triple<M, PcmLineFormat, L>? {
                for (device in devices) for (format in formatsFor(device)) {
                    tryOpen(device, format)?.let { return Triple(device, format, it) }
                }
                return null
            }
            val content: (M) -> List<PcmLineFormat> = { listOf(CONTENT) }
            val converted: (M) -> List<PcmLineFormat> = { device ->
                (preferenceOrder(advertised(device)) + FALLBACKS).distinct().filter { it != CONTENT }
            }
            return attempt(preferred, content) ?: attempt(preferred, converted)
            ?: attempt(other, content) ?: attempt(other, converted)
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
        if (format.float) return packFloat(src, frames, dst, format)
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

    private fun packFloat(src: ByteArray, frames: Int, dst: ByteArray, format: PcmLineFormat) {
        var o = 0
        for (f in 0 until frames) {
            val i = f * 4
            val l = ((src[i].toInt() and 0xFF) or (src[i + 1].toInt() shl 8)).toShort().toInt()
            val r = ((src[i + 2].toInt() and 0xFF) or (src[i + 3].toInt() shl 8)).toShort().toInt()
            if (format.channels == 1) {
                o = put(java.lang.Float.floatToRawIntBits((l + r) / 65_536f), dst, o, 4, format.bigEndian, 0)
            } else {
                o = put(java.lang.Float.floatToRawIntBits(l / 32_768f), dst, o, 4, format.bigEndian, 0)
                o = put(java.lang.Float.floatToRawIntBits(r / 32_768f), dst, o, 4, format.bigEndian, 0)
            }
        }
    }

    /** Writes the 16-bit sample [s] as a [bytes]-wide sample (left-justified, so full scale stays full scale). */
    private fun put(s: Int, dst: ByteArray, at: Int, bytes: Int, bigEndian: Boolean, shift: Int = (bytes - 2) * 8): Int {
        val v = s shl shift
        for (b in 0 until bytes) {
            val byteShift = if (bigEndian) (bytes - 1 - b) * 8 else b * 8
            dst[at + b] = (v shr byteShift).toByte()
        }
        return at + bytes
    }
}
