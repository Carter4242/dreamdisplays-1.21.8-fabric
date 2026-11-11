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
}
