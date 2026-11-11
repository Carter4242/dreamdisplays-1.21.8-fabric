package com.dreamdisplays.core.share

/**
 * The clock a screen share's timestamps run on, so any viewer can tell when a frame was captured.
 *
 * The sharer stamps each frame (its stream PTS) with its capture time on the server's clock (`System.nanoTime` of the
 * server, as each client learns it from the clock sync), less an [epoch]: a multiple of [PERIOD_MICROS] (2^30 ms,
 * about 12 days). The stream's PTS thus stays small enough for FLV's 31-bit millisecond timestamps, travels in-band
 * through the relay untouched, and needs nothing else: a viewer adds back the multiple of the period that puts the
 * capture just before its own now ([captureMicros]). All times in microseconds.
 */
object ShareTimeline {
    /** 2^30 ms: a stream's PTS stays below twice this (FLV's limit is 2^31 ms) for shares up to this long. */
    const val PERIOD_MICROS: Long = (1L shl 30) * 1_000L

    /** The epoch PTS counts from, for a share starting at [serverMicros]. */
    fun epoch(serverMicros: Long): Long = Math.floorDiv(serverMicros, PERIOD_MICROS) * PERIOD_MICROS

    /** Capture time (server clock) of a frame stamped [ptsMicros], seen at [nowServerMicros]: the latest one not after now. */
    fun captureMicros(ptsMicros: Long, nowServerMicros: Long): Long =
        Math.floorDiv(nowServerMicros - ptsMicros, PERIOD_MICROS) * PERIOD_MICROS + ptsMicros

    /** Server clock (µs) from a client's `System.nanoTime` and its offset (client minus server, ns). */
    fun serverMicros(clientNanos: Long, offsetNanos: Long): Long = Math.floorDiv(clientNanos - offsetNanos, 1_000L)

    /** Longest believable glass-to-glass latency; a sample beyond it is a sharer without the clock. */
    const val MAX_LATENCY_MICROS: Long = 10_000_000L

    /**
     * Glass-to-glass latencies (µs) of frames stamped `first` (PTS µs) shown at `second` (this client's
     * `System.nanoTime`), with this client's clock [offsetNanos]; implausible ones (a sharer without the clock) left out.
     */
    fun latenciesMicros(samples: List<Pair<Long, Long>>, offsetNanos: Long): List<Long> = samples.mapNotNull { (pts, at) ->
        val now = serverMicros(at, offsetNanos)
        (now - captureMicros(pts, now)).takeIf { it <= MAX_LATENCY_MICROS }
    }

    /** Median and 95th percentile (ms) of [micros], or null when empty. */
    fun p50p95Ms(micros: List<Long>): Pair<Int, Int>? {
        if (micros.isEmpty()) return null
        val s = micros.sorted()
        fun at(p: Double) = (s[Math.round((s.size - 1) * p).toInt()] / 1_000L).toInt()
        return at(0.5) to at(0.95)
    }
}
