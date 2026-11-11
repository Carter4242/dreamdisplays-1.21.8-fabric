package com.dreamdisplays.core.share

import com.dreamdisplays.api.media.source.LiveStreamUrls

/**
 * The privacy rules behind "Share screen", in one place so every part of the mod (sharer, viewers, server) applies the
 * same ones and they can be tested without a game. A shared screen may show anything on the sharer's desktop, so once
 * a share stops nothing of it may be seen, kept or found again.
 *
 * A viewer cannot tell a screen share from any other live relay stream (`srt://`, `rtmp://`, `rtmps://`, `rtsp://`):
 * every relay stream is treated as private.
 */
object ScreenSharePrivacy {
    /** True when [url] is a live relay stream, which may be someone's screen: see the rules below. */
    fun isPrivateStream(url: String?): Boolean = !url.isNullOrBlank() && LiveStreamUrls.isLive(url)

    /** Whether [url] may be written to any file (history, custom links, display settings, caches). */
    fun mayPersist(url: String?): Boolean = !isPrivateStream(url)

    /** Whether a thumbnail or scrub-preview frame may be generated (and cached) for [url]. */
    fun mayPreview(url: String?): Boolean = !isPrivateStream(url)

    /** Whether [url] may be opened ahead of time (queue preload, warm-up). */
    fun mayPreload(url: String?): Boolean = !isPrivateStream(url)

    /** Whether a display playing [url] may be parked warm or kept as a replay snapshot when it unloads. */
    fun maySnapshot(url: String?): Boolean = !isPrivateStream(url)
}

/** What a viewer's display does about a private stream's picture. */
object ScreenShareViewerRules {
    /** A private stream's picture is hidden once no new frame has come for this long (it ended or stalled). */
    const val STALE_PICTURE_NANOS: Long = 1_000_000_000L

    /**
     * The display's URL changes from [previousUrl] to [newUrl]: true when the old picture must go at once (texture
     * cleared, popouts / PiP / fullscreen closed, preview dropped) instead of staying up until the new video's first
     * frame.
     */
    fun clearOnUrlChange(previousUrl: String?, newUrl: String?): Boolean =
        ScreenSharePrivacy.isPrivateStream(previousUrl) && previousUrl != newUrl

    /**
     * The display's URL changed to [newUrl] after a private stream: true when the old player must be stopped even
     * though there is nothing to play next (an empty URL normally leaves the current player alone).
     */
    fun stopPlayerOnUrlChange(previousUrl: String?, newUrl: String?): Boolean =
        clearOnUrlChange(previousUrl, newUrl) && newUrl.isNullOrEmpty()

    /**
     * True when the display playing [url] must not draw its picture now: [stale] is the player's own "off the air,
     * reconnecting or no recent frame" for a relay stream. Never a held last frame.
     */
    fun hidePicture(url: String?, stale: Boolean): Boolean = ScreenSharePrivacy.isPrivateStream(url) && stale

    /** Whether the switch away from [fromUrl] may keep its last frame up until the next video starts (seamless). */
    fun maySwapSeamlessly(fromUrl: String?): Boolean = !ScreenSharePrivacy.isPrivateStream(fromUrl)
}

/** When the sharer's own client kills its capture. */
object ScreenShareCaptureRules {
    /** Why a capture is killed. Each one kills it at once; [tellServer] says whether the server is told as well. */
    enum class StopReason {
        /** The player pressed Stop. */
        USER_STOP,

        /** The server ended the share (or refused it, or the share was replaced). */
        SERVER_ENDED,

        /** The display the share goes to shows something else now. */
        DISPLAY_URL_CHANGED,

        /** The display was deleted or is no longer known to this client. */
        DISPLAY_REMOVED,

        /** The sharer is in another dimension than the display. */
        OTHER_DIMENSION,

        /** The sharer is too far from the display. */
        OUT_OF_RANGE,

        /** Disconnected, or switched server. */
        SERVER_LEFT,

        /** The client world changed (dimension change, proxy server switch). */
        LEVEL_CHANGED,

        /** The game is quitting. */
        SHUTDOWN,

        /** `FFmpeg` failed to start, exited, or could not publish. */
        CAPTURE_ERROR,
    }

    /** Farthest the sharer may be from the display before the client stops on its own (the server's range is lower). */
    const val MAX_DISTANCE_BLOCKS: Double = 576.0

    /** Whether the server should be told the share is over (it already knows when it ended it, or the link is gone). */
    fun tellServer(reason: StopReason): Boolean = when (reason) {
        StopReason.SERVER_ENDED, StopReason.SERVER_LEFT, StopReason.SHUTDOWN -> false
        else -> true
    }

    /** What the sharer's client knows about the display it shares to, once per tick. */
    data class Observation(
        /** The display is still known (loaded, or held for a distance re-load). */
        val displayKnown: Boolean,
        /** The display's current URL, or null when this client does not have it loaded. */
        val displayUrl: String?,
        /** The share's URL as first seen on the display, or null before it was seen. */
        val shareUrl: String?,
        /** The sharer is in the display's dimension; null when unknown. */
        val sameDimension: Boolean?,
        /** Distance from the sharer to the display in blocks; null when unknown. */
        val distanceBlocks: Double?,
        /** What the display showed when the share was asked for (never taken for the share's own URL). */
        val urlBeforeShare: String? = null,
    )

    /**
     * The share URL to remember after [observation]: the first private stream the display shows during the share
     * that it did not already show before it (the server always hands out a fresh stream id).
     */
    fun shareUrlAfter(observation: Observation): String? = observation.shareUrl
        ?: observation.displayUrl?.takeIf { ScreenSharePrivacy.isPrivateStream(it) && it != observation.urlBeforeShare }

    /** Why the running capture must stop given [o], or null while it may run on. */
    fun check(o: Observation): StopReason? = when {
        !o.displayKnown -> StopReason.DISPLAY_REMOVED
        o.sameDimension == false -> StopReason.OTHER_DIMENSION
        o.distanceBlocks != null && o.distanceBlocks > MAX_DISTANCE_BLOCKS -> StopReason.OUT_OF_RANGE
        // Once the display showed the share, anything else on it means the share is over there
        o.shareUrl != null && o.displayUrl != null && o.displayUrl != o.shareUrl -> StopReason.DISPLAY_URL_CHANGED
        else -> null
    }

    /**
     * The capture watchdog: true when a capture process this client started must be killed. [sharing] is whether the
     * client believes it shares; [isCurrent] whether the process is the current share's own capture.
     */
    fun isOrphan(sharing: Boolean, isCurrent: Boolean): Boolean = !sharing || !isCurrent
}

/** When the server ends a share and what it puts back on the display. */
object ScreenShareServerRules {
    /** How often a sharing client sends a heartbeat. */
    const val HEARTBEAT_INTERVAL_MS: Long = 1_000L

    /** A share whose sharer sent no heartbeat for this long is ended. */
    const val HEARTBEAT_TIMEOUT_MS: Long = 5_000L

    /** Why a share ended. */
    enum class EndReason {
        /** The sharer (or an admin from the menu) asked. */
        REQUESTED,

        /** An op ran `/display share stop`. */
        ADMIN_STOP,

        /** The sharer left the server. */
        SHARER_QUIT,

        /** The sharer is in another world than the display. */
        SHARER_CHANGED_WORLD,

        /** The sharer walked out of the display's range. */
        SHARER_OUT_OF_RANGE,

        /** The sharer's client stopped sending heartbeats. */
        HEARTBEAT_LOST,

        /** The sharer started another share. */
        REPLACED,

        /** Someone set another video by hand: that video stays. */
        VIDEO_SET_BY_HAND,

        /** The display was deleted. */
        DISPLAY_REMOVED,

        /** The plugin is shutting down. */
        PLUGIN_DISABLE,
    }

    /** What the server knows about a running share's sharer, once per check. */
    data class SharerState(
        val online: Boolean,
        val sameWorld: Boolean,
        val inRange: Boolean,
        /** Heartbeats are expected from this sharer (their client sends them). */
        val heartbeatExpected: Boolean,
        /** When the last heartbeat (or the start) arrived, epoch ms. */
        val lastHeartbeatMs: Long,
        val nowMs: Long,
    )

    /** Why the share must end now given [s], or null while it may run on. */
    fun endReason(s: SharerState): EndReason? = when {
        !s.online -> EndReason.SHARER_QUIT
        !s.sameWorld -> EndReason.SHARER_CHANGED_WORLD
        !s.inRange -> EndReason.SHARER_OUT_OF_RANGE
        s.heartbeatExpected && s.nowMs - s.lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS -> EndReason.HEARTBEAT_LOST
        else -> null
    }

    /** Whether ending for [reason] puts the display's previous video back (only while it still shows the share). */
    fun restoresPreviousVideo(reason: EndReason): Boolean =
        reason != EndReason.VIDEO_SET_BY_HAND && reason != EndReason.DISPLAY_REMOVED

    /** Whether the sharer's client is told to stop for [reason] (it already knows when it asked, or it is gone). */
    fun notifiesSharer(reason: EndReason): Boolean = reason != EndReason.SHARER_QUIT
}
