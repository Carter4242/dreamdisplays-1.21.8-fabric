package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.Timeline

/**
 * Where a shared display stood when something took it over for a while (a screen share), so it can be put back exactly
 * there afterwards: [positionMs] into its video, at [rate]. It always comes back paused; someone presses play.
 */
data class SavedPlayback(val positionMs: Long, val rate: Double) {
    /**
     * The timeline to put back at [nowMs]: paused at the saved position (within [durationMs] when that is known), at the
     * saved speed, looping like every shared timeline.
     */
    fun restoredTimeline(nowMs: Long, durationMs: Long): Timeline {
        val position = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
        return Timeline(position, nowMs, paused = true, durationMs = durationMs, loop = true, rate = rate)
    }

    companion object {
        /** [timeline] as it stands at [nowMs] (a start still ahead counts as its start position), or null without one. */
        fun of(timeline: Timeline?, nowMs: Long): SavedPlayback? =
            timeline?.let { SavedPlayback(it.positionAt(nowMs).coerceAtLeast(0L), it.rate) }
    }
}
