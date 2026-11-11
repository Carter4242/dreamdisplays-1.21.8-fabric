package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.queue.QueueTiming

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

    /**
     * The timeline length (ms, 0: unknown) a display has after a base-mode change: the length its viewers reported for
     * the video it holds now ([reportedMediaMs], media ms, 0: none since the video changed or the server started), else
     * the length it had stored for it ([storedMs], timeline ms: persisted, and kept current in every mode). Only the
     * stored one is left when nobody reported since a restart or a watch party, and nobody may report again (older
     * clients report once, at load).
     */
    fun durationAfter(reportedMediaMs: Long, storedMs: Long): Long =
        if (reportedMediaMs > 0L) QueueTiming.scheduledDurationMs(reportedMediaMs) else storedMs.coerceAtLeast(0L)
}
