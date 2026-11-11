package com.dreamdisplays.media.player.pipeline

/**
 * How far behind the player's audio clock the sound actually is, for one display's open output line.
 *
 * The audio clock counts frames the line reports as played, so audio already written but not played yet
 * ([queuedNanos]) is not behind the clock at all; what is, is the output device's own latency below the line
 * ([deviceNanos]): the operating system's mixer and the hardware. OpenAL reports it; Java Sound does not, so it is
 * -1 there and callers use an estimate.
 */
data class AudioOutputLatency(
    val queuedNanos: Long,
    val deviceNanos: Long,
    val backend: String,
)

/** An output line that can report its device latency (see [AudioOutputLatency.deviceNanos]). */
interface LatencyReportingLine {
    /** The output device's latency in ns, or -1 when unknown. Must be cheap and must not block. */
    fun deviceLatencyNanos(): Long
}

/**
 * Receives the display audio a player writes to its line, as the content PCM (44.1 kHz, 16-bit, stereo,
 * little-endian, after volume and acoustics) together with when its first frame leaves the line ([System.nanoTime];
 * add the device latency for when it is heard). Called on the audio thread for every chunk while set: must copy what
 * it needs, must not block and should not allocate.
 */
fun interface PlayedPcmTap {
    fun onPcm(pcm: ByteArray, len: Int, playAtNanos: Long)
}

/** When written audio leaves its line. */
internal object PcmTapTiming {
    /**
     * [System.nanoTime] at which output frame [firstFrame] of a line leaves it, given that the line reported playing
     * frame [linePosition] at [nowNanos] and plays [sampleRate] frames a second (earlier than now when already played).
     */
    fun playAtNanos(nowNanos: Long, firstFrame: Long, linePosition: Long, sampleRate: Int): Long =
        nowNanos + (firstFrame - linePosition) * 1_000_000_000L / sampleRate.coerceAtLeast(1)
}
