package com.dreamdisplays.media.player.adskip

import kotlin.math.ceil

/** When a player switches to its ad-break card, and what the card's countdown says. */
internal object AdBreakTiming {
    /** No picture for this long while the playlist is held: the session played out what came before the ad. */
    const val DRAINED_NANOS = 700_000_000L

    /**
     * Longest the session plays on once the playlist is held: it joined [LiveAdFilter.JOIN_SEGMENTS] segments behind the
     * edge, so it has about that much left (2 s segments), plus slack for its buffers.
     */
    const val MAX_DRAIN_NANOS = 10_000_000_000L

    /** Seconds the countdown adds for the stream to open again once it is back. */
    const val OPEN_ALLOWANCE_SEC = 1

    /**
     * True when a session whose playlist has been held since [holdSinceNanos] has nothing more to show: no new picture
     * for [DRAINED_NANOS] (or none ever, [lastFrameNanos] <= 0), or [MAX_DRAIN_NANOS] since the hold began.
     */
    fun drained(holdSinceNanos: Long, nowNanos: Long, lastFrameNanos: Long): Boolean =
        lastFrameNanos <= 0L || nowNanos - lastFrameNanos > DRAINED_NANOS || nowNanos - holdSinceNanos > MAX_DRAIN_NANOS

    /**
     * Whole seconds the card counts down from: until [resumeAtNanos] plus [OPEN_ALLOWANCE_SEC], rounded up, never
     * below 1 while the card shows. 0 when the stream's return time is unknown (the card then says it starts soon).
     */
    fun secondsLeft(resumeAtNanos: Long?, nowNanos: Long): Int {
        val at = resumeAtNanos ?: return 0
        val sec = ceil((at - nowNanos) / 1e9).toInt() + OPEN_ALLOWANCE_SEC
        return sec.coerceIn(1, MAX_SHOWN_SEC)
    }

    /** Longest countdown shown (a long ad pod is 3 min; past that the number is a guess anyway). */
    const val MAX_SHOWN_SEC = 600
}
