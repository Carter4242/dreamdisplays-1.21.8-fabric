package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.PlaybackAction

/**
 * A display whose video was cleared (or never set): its URL is empty. It has no shared clock, so it never loops, holds
 * a start for readiness or reaches an end; its queue keeps its entries but does not move on by itself.
 */
object EmptyDisplays {
    /** True when a display showing [url] has a video at all. */
    fun hasVideo(url: String): Boolean = url.isNotBlank()

    /**
     * True when a playback [action] on a display showing [url] starts its queue instead: a play on an empty display
     * with something queued ([queueHasNext]). Anything else on an empty display does nothing.
     */
    fun playStartsQueue(url: String, action: PlaybackAction, queueHasNext: Boolean): Boolean =
        !hasVideo(url) && action == PlaybackAction.PLAY && queueHasNext

    /** First protocol whose clients stop a display's player when its URL goes empty (a cleared video). */
    const val CLEAR_PROTOCOL = 12

    /**
     * True when a client on [protocolVersion] (0: unknown) must be told to drop a display whose video was cleared, so
     * the description sent next brings it back as a display with no video: older clients ignore an empty URL and play
     * the cleared video on, unsynced.
     */
    fun dropBeforeClear(protocolVersion: Int): Boolean = protocolVersion < CLEAR_PROTOCOL

    /**
     * True when a client on [protocolVersion] is never described a display showing [url]: an empty one, to a client
     * older than [CLEAR_PROTOCOL]. Such a client takes an empty URL as a change every time it hears it (it rewrites
     * its settings file and asks for the display again, once a second), so it is told nothing until a video is set.
     */
    fun hiddenFrom(url: String, protocolVersion: Int): Boolean = !hasVideo(url) && dropBeforeClear(protocolVersion)
}
