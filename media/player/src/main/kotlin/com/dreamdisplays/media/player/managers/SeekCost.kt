package com.dreamdisplays.media.player.managers

/**
 * Whether a seek on a playing stream is cheap enough for sync's cheap-seek shortcuts (a 300 ms threshold, seconds of
 * cooldown). The last seek alone is not enough: a source that is slow to reopen its sound (a CDN that takes a second
 * or two to answer) mixes in-place seeks of tens of ms with ones that stall for two seconds, and judging only the last
 * one flips the stream back to cheap after every fast seek, so sync keeps seeking and every seek risks the next stall.
 * A slow in-place seek, or sound that had to be restarted because it never arrived, keeps the stream expensive for
 * [SLOW_MEMORY_MS].
 */
internal object SeekCost {
    /** An in-place seek that reached its first frame within this many ms counts as cheap. */
    const val CHEAP_MS = 250L

    /** How long a slow seek or a lost sound keeps seeks expensive. */
    const val SLOW_MEMORY_MS = 60_000L

    /** Whether a seek that took [tookMs] ([inPlace] or a reopen) marks the stream slow. A reopen is expensive anyway. */
    fun marksSlow(tookMs: Long, inPlace: Boolean): Boolean = inPlace && tookMs > CHEAP_MS

    /**
     * Whether seeks are cheap now: no seek yet or the last one went in place within [CHEAP_MS], and nothing marked the
     * stream slow in the last [SLOW_MEMORY_MS] ([sinceSlowMs] null: never).
     */
    fun cheap(lastSeekMs: Long, lastInPlace: Boolean, sinceSlowMs: Long?): Boolean {
        val lastCheap = lastSeekMs < 0L || (lastInPlace && lastSeekMs <= CHEAP_MS)
        return lastCheap && (sinceSlowMs == null || sinceSlowMs >= SLOW_MEMORY_MS)
    }
}
