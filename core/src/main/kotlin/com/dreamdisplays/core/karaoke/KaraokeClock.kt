package com.dreamdisplays.core.karaoke

/**
 * NTP-style offset between a client's `System.nanoTime` and the server's, from ping / pong exchanges the server starts.
 *
 * For one exchange (server sends at `t1`, client receives at `t2` and answers at `t3`, server reads it at `t4`):
 * round trip `(t4 - t1) - (t3 - t2)`, offset (client - server) `((t2 - t1) + (t3 - t4)) / 2`. Queueing on either
 * side (the client handles packets on its render thread, the server on its tick) only ever adds to the round trip,
 * and the offset error is at most half the round trip, so the exchange with the smallest round trip among the recent
 * ones ([maxAgeNanos], so clock rate differences don't pile up) is the estimate.
 *
 * Not thread-safe; the caller synchronises.
 */
class ClockOffsetEstimator(
    private val capacity: Int = 16,
    private val maxAgeNanos: Long = 120_000_000_000L,
) {
    private val rtt = LongArray(capacity)
    private val offset = LongArray(capacity)
    private val at = LongArray(capacity)
    private var count = 0
    private var next = 0

    /** Exchanges kept. */
    val samples: Int get() = count

    /** Adds one exchange; false when it is implausible (negative round trip). */
    fun onExchange(t1ServerNanos: Long, t2ClientNanos: Long, t3ClientNanos: Long, t4ServerNanos: Long): Boolean {
        val roundTrip = (t4ServerNanos - t1ServerNanos) - (t3ClientNanos - t2ClientNanos)
        if (roundTrip < 0 || t3ClientNanos < t2ClientNanos || roundTrip > MAX_RTT_NANOS) return false
        rtt[next] = roundTrip
        offset[next] = ((t2ClientNanos - t1ServerNanos) + (t3ClientNanos - t4ServerNanos)) / 2
        at[next] = t4ServerNanos
        next = (next + 1) % capacity
        if (count < capacity) count++
        return true
    }

    /** Index of the best recent exchange, or -1. */
    private fun best(nowServerNanos: Long): Int {
        var bestIndex = -1
        for (i in 0 until count) {
            if (nowServerNanos - at[i] > maxAgeNanos) continue
            if (bestIndex < 0 || rtt[i] < rtt[bestIndex]) bestIndex = i
        }
        return bestIndex
    }

    /** Client clock minus server clock, ns, or null without a recent exchange. */
    fun offsetNanos(nowServerNanos: Long): Long? = best(nowServerNanos).takeIf { it >= 0 }?.let { offset[it] }

    /** Round trip of the exchange [offsetNanos] comes from (its error bound is half of it), or null. */
    fun rttNanos(nowServerNanos: Long): Long? = best(nowServerNanos).takeIf { it >= 0 }?.let { rtt[it] }

    fun reset() {
        count = 0
        next = 0
    }

    private companion object {
        const val MAX_RTT_NANOS = 5_000_000_000L
    }
}

/**
 * A singer's capture -> server arrival time per voice frame, from the client's capture markers
 * ([com.dreamdisplays.core.protocol.common.packets.KaraokeMicMarkers]) and the clock offset.
 *
 * The voice side records each frame's sequence number and arrival ([onFrame]); a marker says when the client handed
 * frame `seq` over, in its clock. A marker is matched to the frame with that number that arrived closest after it
 * (sequence numbers restart with the client's mic, so only an arrival within [maxLatencyNanos] counts). Like the burst
 * anchor, which sends frames on the jitter-free low end of their arrivals, the result is a low percentile
 * ([percentile]) of the recent latencies.
 *
 * Not thread-safe; the caller synchronises. No allocation per frame.
 */
class UplinkMeter(
    frames: Int = 1_024,
    private val latencies: Int = 32,
    private val percentile: Double = 0.1,
    private val maxLatencyNanos: Long = 3_000_000_000L,
) {
    private val seqs = LongArray(frames) { Long.MIN_VALUE }
    private val arrivals = LongArray(frames)
    private var nextFrame = 0
    private val recent = LongArray(latencies)
    private val sorted = LongArray(latencies)
    private var count = 0
    private var nextLatency = 0

    /** Matched markers kept. */
    val samples: Int get() = count

    /** Records the arrival of frame [seq] (server nanos). */
    fun onFrame(seq: Long, arrivalNanos: Long) {
        seqs[nextFrame] = seq
        arrivals[nextFrame] = arrivalNanos
        nextFrame = (nextFrame + 1) % seqs.size
    }

    /**
     * Matches a marker: frame [seq] captured at [captureClientNanos] on a client whose clock is [offsetNanos] ahead of
     * the server's. Returns the latency in ns, or null when no recorded frame fits.
     */
    fun onMarker(seq: Long, captureClientNanos: Long, offsetNanos: Long): Long? {
        val captured = captureClientNanos - offsetNanos
        var best = Long.MAX_VALUE
        for (i in seqs.indices) {
            if (seqs[i] != seq) continue
            val latency = arrivals[i] - captured
            // A little negative is clock-offset error; more is another frame with the same number
            if (latency < -NEGATIVE_SLACK_NANOS || latency > maxLatencyNanos) continue
            if (latency < best) best = latency
        }
        if (best == Long.MAX_VALUE) return null
        recent[nextLatency] = best
        nextLatency = (nextLatency + 1) % latencies
        if (count < latencies) count++
        return best
    }

    /** The capture -> arrival latency, ns, or null before [minSamples] markers matched. */
    fun latencyNanos(minSamples: Int = 3): Long? {
        if (count < minSamples) return null
        System.arraycopy(recent, 0, sorted, 0, count)
        java.util.Arrays.sort(sorted, 0, count)
        val index = ((count - 1) * percentile).toInt().coerceIn(0, count - 1)
        return sorted[index].coerceAtLeast(0L)
    }

    fun reset() {
        seqs.fill(Long.MIN_VALUE)
        count = 0
        nextLatency = 0
    }

    private companion object {
        const val NEGATIVE_SLACK_NANOS = 20_000_000L
    }
}
