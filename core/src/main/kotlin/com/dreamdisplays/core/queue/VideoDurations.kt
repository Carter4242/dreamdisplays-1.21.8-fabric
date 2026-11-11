package com.dreamdisplays.core.queue

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The media duration each display's video has, from what its viewers' clients measured ([report]), settled by
 * [QueueTiming.mediaDurationMs]. Reports start afresh with every video ([onVideoChanged]); so does each viewer's
 * report cooldown, so a client reloading the video right after a quick switch away and back (a screen share that
 * ended at once) is always heard. The last settled duration of each display is remembered with its URL: a display
 * that goes back to that same video (a share ends, a looping queue replays it) has its length at once, and it is the
 * least that video then runs. Thread-safe.
 *
 * A length read from a header never lowers what is settled: one short report must not end a video early for everyone.
 * A length a client measured where its stream actually ended (`observedEnd`) can: once every viewer still there that
 * reported this video has observed its end, the largest of those ends is the length, even below the header's. One
 * client whose stream stopped early (a cut download) never shortens it while anyone else there says otherwise.
 */
class VideoDurations(private val reportCooldownMs: Long = DEFAULT_REPORT_COOLDOWN_MS) {
    private class Current(
        val url: String, val byPlayer: Map<UUID, Long>, val mediaMs: Long, val lastReportAtMs: Map<UUID, Long>,
        /** Players whose report is where their stream actually ended, not a header length. */
        val observed: Set<UUID> = emptySet(),
    )

    private val current = ConcurrentHashMap<UUID, Current>()
    private val settled = ConcurrentHashMap<UUID, Pair<String, Long>>()

    /** [displayId] now plays [url]: reports start afresh. Returns the media ms settled earlier for this same URL, or 0. */
    fun onVideoChanged(displayId: UUID, url: String): Long {
        val known = settled[displayId]?.takeIf { it.first == url }?.second ?: 0L
        if (known > 0L) current[displayId] = Current(url, emptyMap(), known, emptyMap()) else current.remove(displayId)
        return known
    }

    /** [displayId]'s video [url] is known to last [mediaMs] (a timeline restored after a reload). */
    fun seed(displayId: UUID, url: String, mediaMs: Long) {
        if (mediaMs <= 0L) return
        current[displayId] = Current(url, emptyMap(), mediaMs, emptyMap())
        settled[displayId] = url to mediaMs
    }

    /** The media ms settled for [displayId]'s video [url], or 0. */
    fun mediaMsOf(displayId: UUID, url: String): Long = current[displayId]?.takeIf { it.url == url }?.mediaMs ?: 0L

    /**
     * [playerId]'s client measured [reportedMs] for [displayId]'s current video [url] at [nowMs]; [entryMs] is the
     * playing queue entry's duration (0: none). [observedEnd]: the length is where the client's stream ended, not a
     * header's; [present]: the players watching the display now (see the class comment). Returns the media ms to
     * apply, or null when nothing changes (it is what is known already, or this viewer reported this video less than
     * the cooldown ago).
     */
    fun report(
        displayId: UUID, url: String, playerId: UUID, reportedMs: Long, entryMs: Long, nowMs: Long,
        observedEnd: Boolean = false, present: Collection<UUID> = emptyList(),
    ): Long? {
        var applied: Long? = null
        current.compute(displayId) { _, old ->
            val state = old?.takeIf { it.url == url } ?: Current(url, emptyMap(), 0L, emptyMap())
            val firstObservation = observedEnd && playerId !in state.observed
            val last = state.lastReportAtMs[playerId]
            if (last != null && nowMs - last < reportCooldownMs && !firstObservation) return@compute state
            // Where their stream really ended outranks the header length their client may report again later
            if (!observedEnd && playerId in state.observed) return@compute state
            val byPlayer = state.byPlayer + (playerId to reportedMs)
            val observed = if (observedEnd) state.observed + playerId else state.observed - playerId
            // Never below the playing queue entry's own length, which whole-second metadata only rounds down
            val mediaMs = QueueTiming.observedMediaMs(byPlayer, observed, present)?.coerceAtLeast(entryMs)
                ?: QueueTiming.mediaDurationMs(entryMs, byPlayer.values, state.mediaMs)
            if (mediaMs != state.mediaMs) applied = mediaMs
            Current(url, byPlayer, mediaMs, state.lastReportAtMs + (playerId to nowMs), observed)
        }
        applied?.let { if (it > 0L) settled[displayId] = url to it }
        return applied
    }

    /** Forgets every display. */
    fun clear() {
        current.clear()
        settled.clear()
    }

    /** Forgets a removed display. */
    fun forget(displayId: UUID) {
        current.remove(displayId)
        settled.remove(displayId)
    }

    companion object {
        const val DEFAULT_REPORT_COOLDOWN_MS = 2_000L
    }
}
