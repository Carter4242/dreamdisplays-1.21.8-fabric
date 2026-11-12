package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.packets.DisplaySync

/**
 * Speed changes of a playing shared timeline. Viewers time-stretch the sound in the stream itself, so a new speed
 * means a new audio stream. A scheduled change gives them a short lead to open it in the background: the timeline
 * plays on at the old speed until the switch instant and goes on from there at the new one, with no stop. Otherwise
 * (a viewer whose client cannot follow that, a karaoke singer, live media) the change holds everyone while they
 * restart, as a seek does.
 */
object SpeedChanges {
    /** Most a scheduled change adds to its lead for the slowest viewer's one-way latency. */
    const val MAX_LATENCY_LEAD_MS = 500L

    /** Default preparation lead (ms) before the switch instant. */
    const val DEFAULT_LEAD_MS = 400L

    /**
     * True when a playing display's speed change is scheduled ahead rather than held: a preparation lead is set
     * ([leadMs] > 0), every viewer that runs the mod follows scheduled changes ([viewersCapable]), no karaoke singer
     * gets a lead-shifted timeline ([karaokeLeads]), and the media is not live.
     */
    fun schedules(leadMs: Long, viewersCapable: Boolean, karaokeLeads: Boolean, live: Boolean): Boolean =
        leadMs > 0L && viewersCapable && !karaokeLeads && !live

    /** The whole lead: [prepLeadMs] plus the slowest viewer's one-way latency ([latencyMs], capped). */
    fun leadMs(prepLeadMs: Long, latencyMs: Long): Long =
        prepLeadMs.coerceAtLeast(0L) + latencyMs.coerceIn(0L, MAX_LATENCY_LEAD_MS)

    /**
     * [current] (playing) switching to [rate] [leadMs] after [nowMs]: anchored at the switch instant, where the old
     * speed has brought it, running at [rate] from there (before it, the timeline reads as that position, as any start
     * scheduled ahead does). Null when the old speed would reach the end of a looping timeline by then: the switch
     * would land on the wrap, so the change is held instead.
     */
    fun scheduled(current: Timeline, rate: Double, nowMs: Long, leadMs: Long): Timeline? {
        if (current.paused) return null
        val at = nowMs + leadMs
        if (current.loop && current.durationMs > 0L) {
            val unwrapped = current.positionMs + ((at - current.serverTimeMs).coerceAtLeast(0L) * current.rate).toLong()
            if (unwrapped >= current.durationMs) return null
        }
        return current.withRate(rate, at)
    }

    /**
     * [sync], a timeline packet of a [scheduled] switch, marked as a speed change from [fromRate]. Only while the switch
     * is ahead (a start scheduled ahead): once reached it is a plain playing timeline.
     */
    fun mark(sync: DisplaySync, fromRate: Double): DisplaySync =
        if (!sync.isPaused && sync.startInMs > 0 && fromRate > 0.0) sync.copy(speedChangeFromRate = fromRate) else sync

    /**
     * Client side: true when a timeline packet is a scheduled speed change ([speedChangeFromRate] > 0) the client can
     * follow without stopping, i.e. its player plays the display now ([playing]). Otherwise the packet is handled as
     * the start it also is: paused on the switch position until the start instant.
     */
    fun followsWithoutStop(speedChangeFromRate: Double, paused: Boolean, startInMs: Int, playing: Boolean): Boolean =
        speedChangeFromRate > 0.0 && !paused && startInMs > 0 && playing
}
