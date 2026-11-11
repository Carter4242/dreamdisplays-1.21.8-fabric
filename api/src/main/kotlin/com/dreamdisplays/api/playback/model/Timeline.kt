package com.dreamdisplays.api.playback.model

import kotlin.math.roundToLong

/**
 * Authoritative playback clock; [positionMs] is position as of [serverTimeMs], advances at [rate] (content ms per
 * server ms) while running.
 *
 * @since 1.8.x
 */
data class Timeline(
    val positionMs: Long,
    val serverTimeMs: Long,
    val paused: Boolean,
    val durationMs: Long = 0,
    val loop: Boolean = false,
    /** Playback speed; see [PlaybackSpeed]. @since 1.10.x */
    val rate: Double = PlaybackSpeed.NORMAL,
) {
    /** Position at server-time [nowMs]: advances at [rate] when running, wraps by [durationMs] when [loop]. */
    fun positionAt(nowMs: Long): Long {
        if (paused) return positionMs
        val elapsed = nowMs - serverTimeMs
        val raw = positionMs + if (rate == PlaybackSpeed.NORMAL) elapsed else (elapsed * rate).roundToLong()
        if (raw <= 0) return 0
        return if (loop && durationMs > 0) raw % durationMs else raw
    }

    /** Re-anchors so the clock is continuous at [nowMs] (used before pause / seek / duration changes). */
    fun anchoredAt(nowMs: Long): Timeline = copy(positionMs = positionAt(nowMs), serverTimeMs = nowMs)

    /** A copy paused (or resumed) at [nowMs] without jumping position. */
    fun withPaused(paused: Boolean, nowMs: Long): Timeline = anchoredAt(nowMs).copy(paused = paused)

    /** A copy seeked to [positionMs] at [nowMs], keeping the current pause state. */
    fun seekedTo(positionMs: Long, nowMs: Long): Timeline =
        copy(positionMs = positionMs.coerceAtLeast(0), serverTimeMs = nowMs)

    /** A copy that runs at [rate] from [nowMs] on, without jumping position. */
    fun withRate(rate: Double, nowMs: Long): Timeline = anchoredAt(nowMs).copy(rate = PlaybackSpeed.clamp(rate))

    companion object {
        /** A fresh timeline anchored at [nowMs], position 0. */
        fun start(
            nowMs: Long, paused: Boolean = false, durationMs: Long = 0, loop: Boolean = false,
            rate: Double = PlaybackSpeed.NORMAL,
        ) = Timeline(0, nowMs, paused, durationMs, loop, rate)
    }
}

/**
 * Playback speed limits shared by server and client (content time per real time; 1.0 is normal).
 *
 * @since 1.10.x
 */
object PlaybackSpeed {
    const val NORMAL: Double = 1.0
    const val MIN: Double = 0.1
    const val MAX: Double = 10.0

    /** [rate] clamped into [MIN]..[MAX], rounded to 0.01 so it prints and compares cleanly; non-finite -> [NORMAL]. */
    fun clamp(rate: Double): Double {
        if (!rate.isFinite() || rate <= 0.0) return NORMAL
        return (rate.coerceIn(MIN, MAX) * 100.0).roundToLong() / 100.0
    }

    /** Parses typed text ("1.5", "1,5", "1.5x", "x2") into a clamped speed, or null when it is not a number. */
    fun parse(text: String): Double? {
        val cleaned = text.trim().lowercase().removeSuffix("x").removePrefix("x").replace(',', '.').trim()
        val value = cleaned.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return clamp(value)
    }

    /** Short label: "1.0", "1.25", "0.1", "10.0". */
    fun format(rate: Double): String {
        val r = clamp(rate)
        val whole = r.roundToLong()
        return if (kotlin.math.abs(r - whole) < 1e-9) "$whole.0"
        else java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString()
    }
}
