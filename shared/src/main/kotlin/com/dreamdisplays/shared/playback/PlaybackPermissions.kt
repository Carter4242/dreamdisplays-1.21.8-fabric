package com.dreamdisplays.shared.playback

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackMode.*

/** The single source of truth for who may do what in each [PlaybackMode]. */
object PlaybackPermissions {
    private fun isEditor(c: PlaybackContext): Boolean = c.isOwner || c.isAdmin || !c.isLocked

    /** Max video height for [BROADCAST] displays; never exceeded, not even by the owner. */
    const val BROADCAST_QUALITY_CAP: Int = 720

    /** Play / pause the timeline. Locked displays only allow owner / admin controls, even in `Local`. */
    fun canPlayPause(c: PlaybackContext): Boolean = when (c.mode) {
        LOCAL -> isEditor(c)
        SYNCED -> isEditor(c)
        WATCH_PARTY -> c.isPartyHost
        BROADCAST -> false
    }

    /** Seek the shared timeline (same authority as play / pause). */
    fun canSeek(c: PlaybackContext): Boolean = canPlayPause(c)

    /** Change the display's video URL. */
    fun canSetVideo(c: PlaybackContext): Boolean = when (c.mode) {
        WATCH_PARTY -> c.isPartyHost
        BROADCAST -> c.isOwner || c.isAdmin
        else -> isEditor(c)
    }

    /** Change the persistent base mode. Forbidden while a `Watch party` is live. */
    fun canSetMode(c: PlaybackContext): Boolean =
        (c.isOwner || c.isAdmin || !c.isLocked) && !c.hasActiveParty

    /** Toggle the base lock. Impossible in `Watch party` / `Broadcast` (forced-locked there). */
    fun canToggleLock(c: PlaybackContext): Boolean =
        (c.isOwner || c.isAdmin) && c.mode != WATCH_PARTY && c.mode != BROADCAST

    /** Change the (personal) video quality. `Broadcast` is hard-capped and cannot be changed. */
    fun canChangeQuality(c: PlaybackContext): Boolean =
        c.mode != BROADCAST

    /**
     * Open the `Picture-in-Picture` / `Windowed` popout. Forbidden in `Broadcast` for everyone
     * (owner / admin included).
     */
    fun canPopout(c: PlaybackContext): Boolean =
        c.mode != BROADCAST

    /** Start a watch party: anyone nearby when unlocked, owner / admin when locked. */
    fun canStartWatchParty(c: PlaybackContext): Boolean =
        !c.hasActiveParty && (c.isOwner || c.isAdmin || !c.isLocked)

    /** Drive an active session (begin / pause / seek / end / restart). Host only. */
    fun canControlWatchParty(c: PlaybackContext): Boolean =
        c.isPartyHost

    /** Close a session and free the display. Host, owner, or admin (covers a dead host). */
    fun canCloseWatchParty(c: PlaybackContext): Boolean =
        c.isPartyHost || c.isOwner || c.isAdmin

    /**
     * The "Up next" queue only runs on displays with a server timeline (`Synced` / `Broadcast`); it pauses while a
     * `Watch party` holds the display and does nothing on `Local` displays.
     */
    fun queueRuns(mode: PlaybackMode): Boolean = mode == SYNCED || mode == BROADCAST

    /** Queue a video: whoever may change the video, on a display where the queue runs. */
    fun canAddToQueue(c: PlaybackContext): Boolean = queueRuns(c.mode) && canSetVideo(c)

    /** Skip to the next queued video: same authority as changing the video. */
    fun canSkipQueue(c: PlaybackContext): Boolean = queueRuns(c.mode) && canSetVideo(c)

    /** Remove a queued video: the player who added it ([isAdder]), the owner or an admin. */
    fun canRemoveFromQueue(c: PlaybackContext, isAdder: Boolean): Boolean = isAdder || c.isOwner || c.isAdmin

    /** Reorder, clear, or toggle looping of the queue: owner or admin. */
    fun canManageQueue(c: PlaybackContext): Boolean = c.isOwner || c.isAdmin

    /** The lock the world actually sees: the base lock, or forced on by `Watch party` / `Broadcast`. */
    fun isEffectivelyLocked(mode: PlaybackMode, baseLocked: Boolean): Boolean =
        baseLocked || mode == WATCH_PARTY || mode == BROADCAST
}
