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
 * A packet more than [jumpMs] away from the current estimate, a pause-state change or a new paused position
 * starts a new segment (seek, loop wrap, restart) and drops the older observations.
 */
class TimelineEstimator(
    private val windowMs: Double = 30_000.0,
    private val jumpMs: Double = 1_000.0,
    private val maxObservations: Int = 16,
) {
    private class Observation(val offsetMs: Double, val atMs: Double)

    private val observations = ArrayDeque<Observation>()
    private var pausedPositionMs = 0.0

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
    }

    /**
     * Records a packet that says the server was at [positionMs] ([paused] or not), handled at local monotonic
     * time [nowMs]. Returns true when it started a new segment, i.e. the previous estimate no longer applies.
     */
    fun onPacket(positionMs: Long, paused: Boolean, nowMs: Double, latencyCompMs: Double): Boolean {
        if (paused) {
            val changed = !hasTimeline || !this.paused || positionMs.toDouble() != pausedPositionMs
            observations.clear()
            pausedPositionMs = positionMs.toDouble()
            this.paused = true
            hasTimeline = true
            return changed
        }
        val offset = positionMs + latencyCompMs - nowMs
        val newSegment = !hasTimeline || this.paused || observations.isEmpty() ||
                abs(offset - bestOffset()) > jumpMs
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
        if (paused || observations.isEmpty()) pausedPositionMs else nowMs + bestOffset()

    private fun bestOffset(): Double = observations.maxOf { it.offsetMs }
}
