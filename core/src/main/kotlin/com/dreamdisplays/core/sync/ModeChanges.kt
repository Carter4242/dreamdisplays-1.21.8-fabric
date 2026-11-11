package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.Timeline

/** What a display's shared clock becomes when its base mode changes. */
object ModeChanges {
    /**
     * The timeline after a base-mode change at [nowMs], or null when the new mode has none ([sharedNow] false: Local).
     * Between two shared modes (Synced and Broadcast run the same clock) the [existing] one carries on as it is: paused
     * stays paused, speed and a start hold are kept. From Local (no [existing] timeline) the clock starts playing at
     * [positionMs], 1x, with [durationMs].
     */
    fun timelineAfter(existing: Timeline?, sharedNow: Boolean, positionMs: Long, nowMs: Long, durationMs: Long): Timeline? {
        if (!sharedNow) return null
        if (existing != null) return existing
        return Timeline(positionMs.coerceAtLeast(0L), nowMs, paused = false, durationMs = durationMs, loop = true)
    }
}
