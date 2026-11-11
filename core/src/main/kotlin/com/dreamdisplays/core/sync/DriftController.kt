package com.dreamdisplays.core.sync

import com.dreamdisplays.core.display.policy.SyncSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Decides how to close the gap between local playback and the server timeline while playing.
 *
 * The low seek threshold for cheap seeks is only for streams whose seeks were measured to land within a frame or two;
 * see `MediaPlayer.seeksCheaply`.
 *
 * Drift (positive = behind the server) is smoothed over [FILTER_TAU_MS]. Inside the dead band nothing happens.
 * Beyond it the playback rate is nudged proportionally (closing roughly within [CONVERGE_MS], never more than
 * [SyncSettings.maxRatePct]) until the drift is back under [settleMs], then the rate returns to 1. Past the seek
 * threshold a seek is requested, at most once per cooldown; seeks that follow each other within
 * [STREAK_RESET_MS] stretch the cooldown (up to x4), and that stretch is forgotten once seeks stop. Drift past
 * [LARGE_DRIFT_MS] ignores the stretch: seconds off are never left to a 1 % rate for minutes.
 *
 * The server can take the seeks away ([SyncSettings.correctiveSeeks] false: drift is closed by rate only) or the
 * cheap-seek shortcuts ([SyncSettings.cheapSeeks] false: the configured threshold and cooldown apply to every stream).
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

    /**
     * [observe]s the sample, then decides. [cheapSeeks]: a seek lands within a frame or two (an in-place seek on an
     * in-process decoder), so drift past [CHEAP_SEEK_THRESHOLD_MS] is seeked away instead of rate-corrected for a
     * minute, with a cooldown of at most [CHEAP_SEEK_COOLDOWN_MS].
     */
    fun update(rawDriftMs: Double, nowMs: Double, cheapSeeks: Boolean = false): Decision {
        observe(rawDriftMs, nowMs)
        return decide(nowMs, cheapSeeks)
    }

    /** Decides from the current smoothed drift. */
    fun decide(nowMs: Double, cheapSeeks: Boolean = false): Decision {
        val drift = filteredMs ?: return Decision(1.0, false).also { ratio = 1.0 }
        if (!settings.correctiveSeeks) {
            ratio = rateFor(drift)
            return Decision(ratio, false)
        }
        val cheap = cheapSeeks && settings.cheapSeeks
        val threshold = if (cheap) minOf(settings.seekThresholdMs.toDouble(), CHEAP_SEEK_THRESHOLD_MS)
        else settings.seekThresholdMs.toDouble()
        val stretched = if (abs(drift) > LARGE_DRIFT_MS) settings.seekCooldownMs.toDouble() else currentCooldownMs()
        val cooldown = if (cheap) minOf(stretched, CHEAP_SEEK_COOLDOWN_MS) else stretched
        if (abs(drift) > threshold && nowMs - lastSeekMs >= cooldown) {
            noteSeek(nowMs)
            resetMeasurement()
            return Decision(1.0, true)
        }
        ratio = rateFor(drift)
        return Decision(ratio, false)
    }

    /** Records a seek made outside [decide] (a landing correction), so the cooldown counts from it. */
    fun noteSeek(nowMs: Double) {
        seekStreak = if (nowMs - lastSeekMs < STREAK_RESET_MS) (seekStreak + 1).coerceAtMost(MAX_STREAK) else 0
        lastSeekMs = nowMs
    }

    /** Whether a seek / start that landed [landingDriftMs] off gets one corrective seek at once, outside the cooldown. */
    fun landingNeedsReseek(landingDriftMs: Double): Boolean =
        settings.correctiveSeeks && abs(landingDriftMs) > minOf(settings.seekThresholdMs.toDouble(), LANDING_RESEEK_MS)

    /** Whether a cheap stream ([seeksCheaply]) gets the cheap-seek shortcuts (and seeks at all) under the settings. */
    fun cheapSeeksAllowed(seeksCheaply: Boolean): Boolean =
        seeksCheaply && settings.cheapSeeks && settings.correctiveSeeks

    /**
     * Whether a landing [landingDriftMs] off is re-aimed at once. At most once per correction episode: the landing of
     * a re-aim ([afterReaim]) is accepted and left to rate correction and the cooldown, however far off.
     */
    fun reaimsLanding(landingDriftMs: Double, afterReaim: Boolean): Boolean =
        !afterReaim && landingNeedsReseek(landingDriftMs)

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

        /** Seek threshold while seeks are cheap: rate correction at 1 % would need over 30 s for more than this. */
        const val CHEAP_SEEK_THRESHOLD_MS = 300.0

        /** Longest cooldown between cheap seeks. */
        const val CHEAP_SEEK_COOLDOWN_MS = 4_000.0

        /** Seeks further apart than this reset the cooldown stretch. */
        const val STREAK_RESET_MS = 30_000.0

        private const val MAX_STREAK = 3

        /** Drift past this may seek once the plain cooldown has passed, whatever the streak. */
        const val LARGE_DRIFT_MS = 2_000.0

        /** A landing further off than this (or the seek threshold, if smaller) is re-aimed at once. */
        const val LANDING_RESEEK_MS = 1_000.0
    }
}
