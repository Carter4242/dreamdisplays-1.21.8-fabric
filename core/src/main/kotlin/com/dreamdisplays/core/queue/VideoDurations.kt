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
 *
 * A length known before any report on the current play of a video (restored after a reload, or settled the last time
 * the display played it) has no reporters on record: whoever stood behind it is unknown. Observed ends lower such a
 * length only once every viewer there has reported again; otherwise the first client to report after a reload (one
 * whose stream had stopped early) would settle the video at its own end alone.
 */
class VideoDurations(private val reportCooldownMs: Long = DEFAULT_REPORT_COOLDOWN_MS) {
    private class Current(
        val url: String, val byPlayer: Map<UUID, Long>, val mediaMs: Long, val lastReportAtMs: Map<UUID, Long>,
        /** Players whose report is where their stream actually ended, not a header length. */
        val observed: Set<UUID> = emptySet(),
        /** [mediaMs] was known before these reports: observed ends wait for every viewer there (see the class comment). */
        val inherited: Boolean = false,
    ) {
        /** Observed ends may decide among [present]: always, unless the length was inherited and someone there is silent. */
        fun observedMayDecide(present: Collection<UUID>): Boolean = !inherited || present.all { it in byPlayer }
    }

    private val current = ConcurrentHashMap<UUID, Current>()
    private val settled = ConcurrentHashMap<UUID, Pair<String, Long>>()

    /** What each display's previous video (another URL) was known to last when it changed: see [previousLengthsMs]. */
    private val previous = ConcurrentHashMap<UUID, Current>()

    /**
     * [displayId] now plays [url]: reports start afresh. Returns the media ms it runs from the start: the length settled
     * earlier for this same URL, and at least [entryMs] (the queue entry it plays, 0: none), or 0 when neither is known.
     */
    fun onVideoChanged(displayId: UUID, url: String, entryMs: Long = 0L): Long {
        val before = current[displayId]?.takeIf { it.url != url }
        if (before != null) previous[displayId] = before else previous.remove(displayId)
        val known = settled[displayId]?.takeIf { it.first == url }?.second ?: 0L
        val startMs = maxOf(known, entryMs, 0L)
        if (startMs > 0L) {
            current[displayId] = Current(url, emptyMap(), startMs, emptyMap(), inherited = known > 0L)
        } else {
            current.remove(displayId)
        }
        return startMs
    }

    /** [displayId]'s video [url] is known to last [mediaMs] (a timeline restored after a reload). */
    fun seed(displayId: UUID, url: String, mediaMs: Long) {
        if (mediaMs <= 0L) return
        current[displayId] = Current(url, emptyMap(), mediaMs, emptyMap(), inherited = true)
        settled[displayId] = url to mediaMs
    }

    /**
     * The lengths (ms) known for the video [displayId] played before its current one, which had another URL: the one
     * settled and each viewer's report. Empty when that video's length was never known (or the display had none).
     */
    fun previousLengthsMs(displayId: UUID): List<Long> =
        previous[displayId]?.let { listOf(it.mediaMs) + it.byPlayer.values }?.filter { it > 0L }.orEmpty()

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
            val next = Current(url, byPlayer, state.mediaMs, state.lastReportAtMs + (playerId to nowMs), observed, state.inherited)
            // Never below the playing queue entry's own length, which whole-second metadata only rounds down
            val observedMs = if (next.observedMayDecide(present)) QueueTiming.observedMediaMs(byPlayer, observed, present) else null
            val mediaMs = observedMs?.coerceAtLeast(entryMs) ?: QueueTiming.mediaDurationMs(entryMs, byPlayer.values, state.mediaMs)
            if (mediaMs != state.mediaMs) applied = mediaMs
            Current(url, byPlayer, mediaMs, next.lastReportAtMs, observed, state.inherited)
        }
        applied?.let { if (it > 0L) settled[displayId] = url to it }
        return applied
    }

    /**
     * Settles [displayId]'s video [url] again for the players watching now ([present]): someone who reported and left
     * no longer stands in the way of the observed ends of those still there (see the class comment). Returns the media
     * ms to apply, or null when nothing changes.
     */
    fun recheck(displayId: UUID, url: String, entryMs: Long, present: Collection<UUID>): Long? {
        var applied: Long? = null
        current.computeIfPresent(displayId) { _, state ->
            if (state.url != url || state.observed.isEmpty() || !state.observedMayDecide(present)) return@computeIfPresent state
            val mediaMs = QueueTiming.observedMediaMs(state.byPlayer, state.observed, present)?.coerceAtLeast(entryMs)
            if (mediaMs == null || mediaMs == state.mediaMs) return@computeIfPresent state
            applied = mediaMs
            Current(url, state.byPlayer, mediaMs, state.lastReportAtMs, state.observed, state.inherited)
        }
        applied?.let { settled[displayId] = url to it }
        return applied
    }

    /** Forgets every display. */
    fun clear() {
        current.clear()
        settled.clear()
        previous.clear()
    }

    /** Forgets a removed display. */
    fun forget(displayId: UUID) {
        current.remove(displayId)
        settled.remove(displayId)
        previous.remove(displayId)
    }

    companion object {
        const val DEFAULT_REPORT_COOLDOWN_MS = 2_000L
    }
}
