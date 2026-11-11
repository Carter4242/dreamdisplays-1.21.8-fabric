package com.dreamdisplays.core.karaoke

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One singer's automatic trim, from their client's offset reports ("my voice is X ms late against the track I
 * heard", see [VoiceTrackAligner]). The offset is absolute, not relative to the current trim (the singer's client
 * measures its own voice against its own track, which the server's send timing does not touch), so the trim simply
 * heads for `-median(recent offsets)`:
 *
 * - reports under [minConfidence] or beyond [maxOffsetMs] are ignored;
 * - the target is the median of the last [window] accepted reports, and needs [minReports] of them;
 * - the trim moves toward it by at most [maxStepMs], at most once every [minIntervalMs], and stays within
 *   +-[clampMs], so one bad window can't throw a song off and listeners never hear a jump.
 *
 * The manual trims add on top. Not thread-safe; the caller synchronises.
 */
class AutoTrimController(
    private val maxStepMs: Long = 20L,
    private val minIntervalMs: Long = 5_000L,
    private val clampMs: Long = 600L,
    private val minConfidence: Double = 0.35,
    private val window: Int = 7,
    private val minReports: Int = 3,
    private val maxOffsetMs: Double = 1_000.0,
) {
    private val recent = DoubleArray(window)
    private var count = 0
    private var next = 0
    private var lastStepMs = Long.MIN_VALUE / 2

    /** The automatic trim now, ms (negative = the voice goes out earlier). */
    var trimMs: Long = 0L
        private set

    /** Confidence of the last accepted report (0 before any). */
    var confidence: Double = 0.0
        private set

    /** Accepted reports in the current window. */
    val reports: Int get() = count

    /** The median offset of the current window, or null with fewer than [minReports]. */
    fun medianOffsetMs(): Double? {
        if (count < minReports) return null
        val sorted = recent.copyOf(count).also { it.sort() }
        return if (count % 2 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
    }

    /** Takes one report; returns true when the trim moved. */
    fun onReport(offsetMs: Double, confidence: Double, nowMs: Long): Boolean {
        if (!offsetMs.isFinite() || !confidence.isFinite()) return false
        if (confidence < minConfidence || abs(offsetMs) > maxOffsetMs) return false
        this.confidence = confidence.coerceIn(0.0, 1.0)
        recent[next] = offsetMs
        next = (next + 1) % window
        if (count < window) count++
        if (nowMs - lastStepMs < minIntervalMs) return false
        val median = medianOffsetMs() ?: return false
        val target = (-median).roundToLong().coerceIn(-clampMs, clampMs)
        val step = (target - trimMs).coerceIn(-maxStepMs, maxStepMs)
        if (step == 0L) return false
        trimMs = (trimMs + step).coerceIn(-clampMs, clampMs)
        lastStepMs = nowMs
        return true
    }

    /** Forgets the reports (a new song or stage) but keeps the trim learned so far. */
    fun resetReports() {
        count = 0
        next = 0
        confidence = 0.0
    }
}
