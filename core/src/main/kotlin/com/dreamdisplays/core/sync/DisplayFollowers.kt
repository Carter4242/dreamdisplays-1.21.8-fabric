package com.dreamdisplays.core.sync

import java.util.*

/**
 * Who hears about a display beyond the players in its range. Two kinds of far followers:
 * - fullscreen followers (a broadcast's viewers, clients prepared for one): they hold the display loaded at any
 *   distance, are waited for at a start, and count as watching it;
 * - PiP pinners: they get every change too, but a pin can outlive the client's PiP (a crash, a lost unpin), so they
 *   are never waited for, and their description never makes a client load a display it does not hold.
 */
object DisplayFollowers {
    /**
     * The far players to send [displayId]'s description (DisplayInfo) to, beyond [nearby], each with the `forced` flag
     * to send: true (load at any distance) for fullscreen followers, false for pinners only.
     */
    fun infoTargets(nearby: Collection<UUID>, fullscreen: Collection<UUID>, pinners: Collection<UUID>): Map<UUID, Boolean> {
        val near = nearby.toSet()
        val out = LinkedHashMap<UUID, Boolean>()
        for (id in pinners) if (id !in near) out[id] = false
        for (id in fullscreen) if (id !in near) out[id] = true
        return out
    }

    /** Far players who get [displayId]'s timeline, queue and deletion: every follower outside [nearby], once each. */
    fun farReceivers(nearby: Collection<UUID>, fullscreen: Collection<UUID>, pinners: Collection<UUID>): List<UUID> {
        val near = nearby.toSet()
        return (fullscreen.asSequence() + pinners.asSequence()).filter { it !in near }.distinct().toList()
    }
}
