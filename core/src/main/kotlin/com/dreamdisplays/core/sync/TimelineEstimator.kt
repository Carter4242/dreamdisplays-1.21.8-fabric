package com.dreamdisplays.core.sync

import kotlin.math.abs

/**
 * Client-side estimate of the server's playback position between timeline packets.
 *
 * Each playing packet says "the server was at P when it sent this"; by the time it is handled it is late by the
 * one-way trip plus however long the client took to get to it. The trip is compensated with half the round
 * trip ([onPacket]'s `latencyCompMs`); the handling delay only ever makes a packet look *earlier*, so the
 * estimate uses the packet that implies the furthest-along timeline among those received in the last
 * [windowMs] (a window, not forever, so server / client clock-rate differences cannot accumulate).
 *
 * A packet more than [jumpMs] away from the current estimate, a pause-state change, a new paused position or a new
 * playback [rate] starts a new segment (seek, loop wrap, restart, speed change) and drops the older observations.
 *
 * At a [rate] other than 1 the server position advances that many content ms per local ms, so observations are
 * kept as content offsets against `nowMs * rate`.
 */
class TimelineEstimator(
    private val windowMs: Double = 30_000.0,
    private val jumpMs: Double = 1_000.0,
    private val maxObservations: Int = 16,
) {
    private class Observation(val offsetMs: Double, val atMs: Double)

    private val observations = ArrayDeque<Observation>()
    private var pausedPositionMs = 0.0

    /** Content ms per local ms of the current segment (the server's playback speed). */
    var rate: Double = 1.0
        private set

    /** True once any packet has been seen since the last [reset]. */
    var hasTimeline: Boolean = false
        private set

    /** Whether the server timeline is paused. */
    var paused: Boolean = true
        private set

    /** Forgets everything (new video). */
    fun reset() {
        observations.clear()
        hasTimeline = false
        paused = true
        pausedPositionMs = 0.0
        rate = 1.0
    }

    /**
     * Records a packet that says the server was at [positionMs] ([paused] or not), handled at local monotonic
     * time [nowMs]. Returns true when it started a new segment, i.e. the previous estimate no longer applies.
     */
    fun onPacket(
        positionMs: Long, paused: Boolean, nowMs: Double, latencyCompMs: Double, rate: Double = 1.0,
    ): Boolean {
        val rateChanged = rate != this.rate
        this.rate = rate
        if (paused) {
            val changed = !hasTimeline || !this.paused || positionMs.toDouble() != pausedPositionMs || rateChanged
            observations.clear()
            pausedPositionMs = positionMs.toDouble()
            this.paused = true
            hasTimeline = true
            return changed
        }
        val offset = positionMs + (latencyCompMs - nowMs) * rate
        val newSegment = !hasTimeline || this.paused || observations.isEmpty() || rateChanged ||
                abs(offset - bestOffset()) > jumpMs * rate.coerceAtLeast(1.0)
        if (newSegment) observations.clear()
        observations.addLast(Observation(offset, nowMs))
        while (observations.size > 1 && nowMs - observations.first().atMs > windowMs) observations.removeFirst()
        while (observations.size > maxObservations) observations.removeFirst()
        this.paused = false
        hasTimeline = true
        return newSegment
    }

    /** The estimated server position at local time [nowMs] (unwrapped; the caller applies looping). */
    fun targetMs(nowMs: Double): Double =
        if (paused || observations.isEmpty()) pausedPositionMs else nowMs * rate + bestOffset()

    /**
     * A position difference of [contentMs] as real time at the current [rate]: how far apart two viewers are in
     * what they show now. Paused, there is no pace to divide by, so it stays content time.
     */
    fun toRealMs(contentMs: Double): Double = if (paused || rate == 1.0) contentMs else contentMs / rate

    private fun bestOffset(): Double = observations.maxOf { it.offsetMs }
}
