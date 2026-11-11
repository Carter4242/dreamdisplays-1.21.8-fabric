package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.RequestSync
import com.dreamdisplays.platform.client.Initializer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The media generation ([DisplayInfo.mediaGen]) of each display as the server last described it, loaded or not, and of
 * the video it actually shows ([onScreen]). The latter is echoed in everything this client says about a display's
 * video, so the server can tell it apart from talk about another video; 0 = unknown (an older server, or no
 * description yet). The two differ only while a preloaded queue entry waits for its scheduled start: the server has
 * described the next video already, while the current one still plays (and is what a seek or pause acts on).
 *
 * A timeline for another generation means this client missed a description (or never got one): it asks for the
 * display again ([RequestSync.knownMediaGen]), at most every [REREQUEST_MS] per display.
 */
object ServerMediaGens {
    private const val REREQUEST_MS = 2_000L

    private val gens = ConcurrentHashMap<UUID, Long>()
    private val requestedAt = ConcurrentHashMap<UUID, Long>()

    /** The generation each display was described with before its latest change of generation. */
    private val previous = ConcurrentHashMap<UUID, Long>()

    /** Displays still showing the video of an older generation (a pending queue switch), and that generation. */
    private val held = ConcurrentHashMap<UUID, Long>()

    /** The generation the server last described [displayId] with, or 0. Only for asking about descriptions. */
    fun of(displayId: UUID): Long = gens[displayId] ?: 0L

    /** The generation of the video [displayId] shows right now, or 0: what everything said about that video carries. */
    fun onScreen(displayId: UUID): Long = held[displayId] ?: of(displayId)

    /**
     * The generation of the video [displayId] showed before its latest description: the one still up while a switch
     * to that description waits. Read it before the switch that is replaced is [release]d.
     */
    fun beforeLatest(displayId: UUID): Long = held[displayId] ?: previous[displayId] ?: 0L

    /** [displayId] keeps showing the video of generation [gen] (see [beforeLatest]) until [release]. */
    fun hold(displayId: UUID, gen: Long) {
        held[displayId] = gen
    }

    /** The described video is now the one on screen (the switch happened, or was dropped). */
    fun release(displayId: UUID) {
        held.remove(displayId)
    }

    /** True if [displayId] has a description from the server. */
    fun knows(displayId: UUID): Boolean = gens.containsKey(displayId)

    /** Records [info]'s generation; true when it differs from the one held before (a known display got a new video). */
    fun onInfo(info: DisplayInfo): Boolean {
        val before = gens.put(info.id, info.mediaGen)
        val changed = before != null && before != info.mediaGen
        if (changed) previous[info.id] = before!!
        return changed
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
        previous.remove(displayId)
        held.remove(displayId)
    }

    /** Disconnect / backend switch. */
    fun reset() {
        gens.clear()
        requestedAt.clear()
        previous.clear()
        held.clear()
    }
}

/**
 * Whether a timeline stamped [timelineGen] (0 = an older server, which stamps nothing) belongs to a video other than
 * the one this client was told about ([knownGen], null = no description at all).
 */
internal fun timelineNeedsDescription(timelineGen: Long, knownGen: Long?): Boolean =
    timelineGen != 0L && timelineGen != knownGen
