package com.dreamdisplays.core.sync

import java.util.*

/**
 * Who hears about a display beyond the players in its range. Two kinds of far followers:
 * - fullscreen followers (a broadcast's viewers, clients prepared for one): they hold the display loaded at any
 *   distance, are waited for at a start, and count as watching it;
 * - PiP pinners: they get every change too, and their description never makes a client load a display it does not
 *   hold. A pinner whose client shows the display ([showing]: it never said it does not play it) is its audience like
 *   a player in range: waited for at a start, counted as watching, and may act on it from afar ([present]). A pin can
 *   outlive the client's PiP (a crash, a lost unpin); that client reports the display absent and drops out again.
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

    /**
     * Whether [playerId]'s duration report counts for a display: a viewer in range or any far follower; they play it
     * too, and when they are its only audience (a broadcast nobody stands near) nobody else can report its length.
     */
    fun mayReportDuration(playerId: UUID, nearby: Collection<UUID>, fullscreen: Collection<UUID>, pinners: Collection<UUID>): Boolean =
        playerId in nearby || playerId in fullscreen || playerId in pinners

    /**
     * The players a start of a display waits for and times its lead by: everyone in range, every fullscreen follower and
     * every pinner [showing] it, once each. A queue switch counts their preloads too: with nobody in range (a broadcast's
     * far audience, PiP viewers) theirs are the only ones.
     */
    fun audience(nearby: Collection<UUID>, fullscreen: Collection<UUID>, pinViewers: Collection<UUID> = emptyList()): List<UUID> =
        (nearby.asSequence() + fullscreen.asSequence() + pinViewers.asSequence()).distinct().toList()

    /** Whether a display counts as watched for auto-pause: anyone in range, a fullscreen follower or a PiP viewer. */
    fun watched(nearby: Collection<UUID>, fullscreen: Collection<UUID>, pinViewers: Collection<UUID> = emptyList()): Boolean =
        nearby.isNotEmpty() || fullscreen.isNotEmpty() || pinViewers.isNotEmpty()

    /**
     * Of [pinners] (online, with the display pinned), those whose client shows it: all but the ones it [reportedAbsent]
     * for (their client does not play it: the PiP closed without an unpin reaching the server, or never reopened).
     */
    fun showing(pinners: Collection<UUID>, reportedAbsent: (UUID) -> Boolean): List<UUID> =
        pinners.filter { !reportedAbsent(it) }

    /**
     * Whether [playerId] may act on a display as one of its viewers (playback, video, queue; the display's access and
     * the player's permissions still decide what): in its range ([inRange]), or watching it in PiP ([pinViewers]).
     */
    fun present(playerId: UUID, inRange: Boolean, pinViewers: Collection<UUID>): Boolean = inRange || playerId in pinViewers
}
