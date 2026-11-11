package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The last timeline the server sent for each display, kept while the display is not loaded here (past this client's
 * own distance or budget, while the server still counts it in range and keeps its timeline current). A display loaded
 * again starts its stream where the shared clock is now instead of at its beginning, before the timeline it asks the
 * server for arrives (that one still corrects it).
 */
internal object RecentTimelines {
    private class Entry(val packet: DisplaySync, val url: String, val receivedNanos: Long)

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** Records [packet], received for the display now showing (or last known to show) [url]. */
    fun record(packet: DisplaySync, url: String?, nowNanos: Long = System.nanoTime()) {
        if (url.isNullOrEmpty()) return
        entries[packet.id] = Entry(packet, url, nowNanos)
    }

    /**
     * The recorded timeline for [displayId] as a packet to apply now, while it still describes [url] and media
     * generation [mediaGen] (0 = unknown) and is at most [MAX_AGE_MS] old; null otherwise.
     */
    fun replayable(displayId: UUID, url: String, mediaGen: Long, nowNanos: Long = System.nanoTime()): DisplaySync? {
        val entry = entries[displayId] ?: return null
        return replayedTimeline(entry.packet, entry.url, (nowNanos - entry.receivedNanos) / 1_000_000.0, url, mediaGen)
    }

    fun forget(displayId: UUID) {
        entries.remove(displayId)
    }

    fun reset() {
        entries.clear()
    }

    /** Older than this, the server may well have paused or moved the timeline without this client hearing of it. */
    const val MAX_AGE_MS = 15_000.0
}

/**
 * [packet] (recorded for [recordedUrl] [ageMs] ago) as a packet to apply now for a display showing [url] at media
 * generation [mediaGen], or null when it no longer describes that video or is too old. A playing timeline is replayed
 * as a start that happened [ageMs] ago, so the follower projects it to now (see [replayedStartInMs]).
 */
internal fun replayedTimeline(packet: DisplaySync, recordedUrl: String, ageMs: Double, url: String, mediaGen: Long): DisplaySync? {
    if (recordedUrl != url || ageMs !in 0.0..RecentTimelines.MAX_AGE_MS) return null
    if (packet.mediaGen != 0L && mediaGen != 0L && packet.mediaGen != mediaGen) return null
    if (packet.isPaused) return packet
    return packet.copy(startInMs = replayedStartInMs(packet.startInMs, ageMs))
}
