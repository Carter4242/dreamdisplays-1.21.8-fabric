package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.api.playback.model.Timeline
import kotlinx.serialization.Serializable
import java.util.*

/**
 * The stored (JSON) form of the server's display timelines, written when the plugin is disabled and read once when it
 * is enabled again, so a reload or restart neither restarts every display from 0 nor starts paused ones playing.
 * Timelines are anchored on wall time, so a playing one comes back where it would be had the plugin never stopped.
 */
object TimelineRecords {
    /** Past this long unloaded, a playing timeline with no known duration resumes where it stopped instead. */
    const val MAX_UNKNOWN_DURATION_GAP_MS = 10L * 60 * 1_000

    /** One display's stored timeline; [url] is the video it belongs to, [mediaMs] its settled media duration. */
    @Serializable
    data class Saved(
        val displayId: String,
        val url: String,
        val positionMs: Long,
        val serverTimeMs: Long,
        val paused: Boolean,
        val durationMs: Long = 0,
        val loop: Boolean = true,
        val rate: Double = PlaybackSpeed.NORMAL,
        val mediaMs: Long = 0,
        val savedAtMs: Long = 0,
    )

    /**
     * The record for [timeline] of [displayId] playing [url] at [nowMs]. A timeline in a start hold ([holding]: paused
     * only until its viewers are ready) is stored as playing from its held position, since it was about to start.
     */
    fun save(displayId: UUID, url: String, timeline: Timeline, holding: Boolean, mediaMs: Long, nowMs: Long): Saved {
        val t = if (holding && timeline.paused) timeline.copy(serverTimeMs = nowMs, paused = false) else timeline
        return Saved(
            displayId.toString(), url, t.positionMs, t.serverTimeMs, t.paused, t.durationMs, t.loop, t.rate,
            mediaMs, nowMs,
        )
    }

    /**
     * The timeline [saved] stands for at [nowMs]: position, pause, speed and anchor as stored, so the time spent
     * unloaded counts for a playing one (a paused one stays put). After a long gap a playing timeline with no known
     * duration resumes at the position it had when saved; it has no end to wrap at.
     */
    fun restore(saved: Saved, nowMs: Long): Timeline {
        val timeline = Timeline(
            positionMs = saved.positionMs.coerceAtLeast(0L),
            serverTimeMs = saved.serverTimeMs,
            paused = saved.paused,
            durationMs = saved.durationMs.coerceAtLeast(0L),
            loop = saved.loop,
            rate = PlaybackSpeed.clamp(saved.rate),
        )
        if (timeline.paused || timeline.durationMs > 0L) return timeline
        if (saved.savedAtMs <= 0L || nowMs - saved.savedAtMs <= MAX_UNKNOWN_DURATION_GAP_MS) return timeline
        return timeline.anchoredAt(saved.savedAtMs).copy(serverTimeMs = nowMs)
    }
}
