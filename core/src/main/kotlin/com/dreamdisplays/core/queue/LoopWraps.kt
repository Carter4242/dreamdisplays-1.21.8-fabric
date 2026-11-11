package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.api.playback.model.Timeline
import kotlin.math.roundToLong

/**
 * A looping timeline whose end the server ticked past without restarting it (a main-thread stall longer than the
 * restart's early window): it wrapped silently, while clients hold the last frame until they hear a timeline again.
 */
object LoopWraps {
    /**
     * Which play-through of [timeline] runs at [nowMs] (0 for the first), or null when it does not wrap by itself
     * (paused, not looping, or [durationMs] is not its own length).
     */
    fun playThrough(timeline: Timeline, nowMs: Long, durationMs: Long): Long? {
        if (timeline.paused || !timeline.loop || durationMs <= 0L || timeline.durationMs != durationMs) return null
        return raw(timeline, nowMs) / durationMs
    }

    /** True when the play-through went from [previous] (last tick, same timeline) to a later [current] one. */
    fun missedWrap(previous: Long?, current: Long?): Boolean = previous != null && current != null && current > previous

    /** [timeline] re-anchored at [nowMs] where it stands now (in its new play-through), to send as is. */
    fun reanchored(timeline: Timeline, nowMs: Long): Timeline = timeline.anchoredAt(nowMs)

    private fun raw(timeline: Timeline, nowMs: Long): Long {
        if (nowMs <= timeline.serverTimeMs) return timeline.positionMs.coerceAtLeast(0L)
        val elapsed = nowMs - timeline.serverTimeMs
        val advanced = if (timeline.rate == PlaybackSpeed.NORMAL) elapsed else (elapsed * timeline.rate).roundToLong()
        return (timeline.positionMs + advanced).coerceAtLeast(0L)
    }
}
