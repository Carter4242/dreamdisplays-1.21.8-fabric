package com.dreamdisplays.core.sync

import com.dreamdisplays.core.display.policy.SyncSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Decides how to close the gap between local playback and the server timeline while playing.
 *
 * Drift (positive = behind the server) is smoothed over [FILTER_TAU_MS]. Inside the dead band nothing happens.
 * Beyond it the playback rate is nudged proportionally (closing roughly within [CONVERGE_MS], never more than
 * [SyncSettings.maxRatePct]) until the drift is back under [settleMs], then the rate returns to 1. Past the seek
 * threshold a seek is requested, at most once per cooldown; seeks that follow each other within
 * [STREAK_RESET_MS] stretch the cooldown (up to x4), and that stretch is forgotten once seeks stop.
 */
class DriftController(var settings: SyncSettings = SyncSettings.DEFAULT) {
    /** What to do now: play at [ratio] (content per output time) or [seek] to the target. */
    data class Decision(val ratio: Double, val seek: Boolean)

    /** Smoothed drift in ms, or null until a sample arrives after a reset. */
    var filteredMs: Double? = null
        private set

    /** Rate ratio last decided (1.0 when idle). */
    var ratio: Double = 1.0
        private set

    private var lastSampleMs = Double.NaN
    private var correcting = false
    private var lastSeekMs = Double.NEGATIVE_INFINITY
    private var seekStreak = 0

    /** Drops the measurement (after a seek, restart, new timeline segment) and returns the rate to 1. */
    fun resetMeasurement() {
        filteredMs = null
        lastSampleMs = Double.NaN
        correcting = false
        ratio = 1.0
    }

    /** Forgets everything, including seek history (new video). */
    fun reset() {
        resetMeasurement()
        lastSeekMs = Double.NEGATIVE_INFINITY
        seekStreak = 0
    }

    /** Feeds one raw drift sample taken at local time [nowMs]; returns the smoothed drift. */
    fun observe(rawDriftMs: Double, nowMs: Double): Double {
        val previous = filteredMs
        val dt = if (lastSampleMs.isNaN()) 0.0 else (nowMs - lastSampleMs).coerceAtLeast(0.0)
        lastSampleMs = nowMs
        val next = if (previous == null) rawDriftMs
        else previous + (rawDriftMs - previous) * (1.0 - exp(-dt / FILTER_TAU_MS))
        filteredMs = next
        return next
    }

    /** [observe]s the sample, then decides. */
    fun update(rawDriftMs: Double, nowMs: Double): Decision {
        observe(rawDriftMs, nowMs)
        return decide(nowMs)
    }

    /** Decides from the current smoothed drift. */
    fun decide(nowMs: Double): Decision {
        val drift = filteredMs ?: return Decision(1.0, false).also { ratio = 1.0 }
        if (abs(drift) > settings.seekThresholdMs && nowMs - lastSeekMs >= currentCooldownMs()) {
            seekStreak = if (nowMs - lastSeekMs < STREAK_RESET_MS) (seekStreak + 1).coerceAtMost(MAX_STREAK) else 0
            lastSeekMs = nowMs
            resetMeasurement()
            return Decision(1.0, true)
        }
        ratio = rateFor(drift)
        return Decision(ratio, false)
    }

    /** Cooldown before the next seek may happen, given the recent streak. */
    fun currentCooldownMs(): Double = settings.seekCooldownMs.toDouble() * (1 + seekStreak)

    private fun rateFor(drift: Double): Double {
        val max = settings.maxRateFraction
        if (!settings.enabled || max <= 0.0) {
            correcting = false
            return 1.0
        }
        val magnitude = abs(drift)
        if (!correcting && magnitude > settings.deadBandMs) correcting = true
        if (correcting && magnitude <= settleMs()) correcting = false
        if (!correcting) return 1.0
        val offset = (magnitude / CONVERGE_MS).coerceIn(MIN_RATE.coerceAtMost(max), max)
        return 1.0 + sign(drift) * offset
    }

    private fun settleMs(): Double = minOf(SETTLE_MS, settings.deadBandMs * 0.4)

    companion object {
        /** Smoothing time constant of the drift measurement. */
        const val FILTER_TAU_MS = 500.0

        /** Rate correction stops once drift is back within this (or 40 % of the dead band, if smaller). */
        const val SETTLE_MS = 15.0

        /** Proportional gain: the rate offset closes the current drift in about this long. */
        const val CONVERGE_MS = 10_000.0

        /** Smallest rate offset used while correcting, so the last few ms still close in seconds. */
        const val MIN_RATE = 0.001

        /** Seeks further apart than this reset the cooldown stretch. */
        const val STREAK_RESET_MS = 30_000.0

        private const val MAX_STREAK = 3
    }
}
