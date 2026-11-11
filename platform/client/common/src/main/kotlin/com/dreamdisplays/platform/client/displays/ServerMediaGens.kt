package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.RequestSync
import com.dreamdisplays.platform.client.Initializer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The media generation ([DisplayInfo.mediaGen]) of each display as the server last described it, loaded or not. Echoed
 * in everything this client says about a display's video, so the server can tell it apart from talk about the video
 * the display held before; 0 = unknown (an older server, or no description yet).
 *
 * A timeline for another generation means this client missed a description (or never got one): it asks for the
 * display again ([RequestSync.knownMediaGen]), at most every [REREQUEST_MS] per display.
 */
object ServerMediaGens {
    private const val REREQUEST_MS = 2_000L

    private val gens = ConcurrentHashMap<UUID, Long>()
    private val requestedAt = ConcurrentHashMap<UUID, Long>()

    /** The generation the server last described [displayId] with, or 0. */
    fun of(displayId: UUID): Long = gens[displayId] ?: 0L

    /** True if [displayId] has a description from the server. */
    fun knows(displayId: UUID): Boolean = gens.containsKey(displayId)

    /** Records [info]'s generation; true when it differs from the one held before (a known display got a new video). */
    fun onInfo(info: DisplayInfo): Boolean {
        val previous = gens.put(info.id, info.mediaGen)
        return previous != null && previous != info.mediaGen
    }

    /**
     * Checks [sync] against the description this client holds and asks for the display again when they disagree.
     * Returns true when it asked.
     */
    fun onSync(sync: DisplaySync, nowMs: Long = System.currentTimeMillis()): Boolean {
        val known = gens[sync.id]
        if (!timelineNeedsDescription(sync.mediaGen, known)) return false
        val last = requestedAt[sync.id]
        if (last != null && nowMs - last < REREQUEST_MS) return false
        requestedAt[sync.id] = nowMs
        Initializer.sendPacket(RequestSync(sync.id, known ?: 0L))
        return true
    }

    /** The display was deleted or evicted. */
    fun forget(displayId: UUID) {
        gens.remove(displayId)
        requestedAt.remove(displayId)
    }

    /** Disconnect / backend switch. */
    fun reset() {
        gens.clear()
        requestedAt.clear()
    }
}

/**
 * Whether a timeline stamped [timelineGen] (0 = an older server, which stamps nothing) belongs to a video other than
 * the one this client was told about ([knownGen], null = no description at all).
 */
internal fun timelineNeedsDescription(timelineGen: Long, knownGen: Long?): Boolean =
    timelineGen != 0L && timelineGen != knownGen
