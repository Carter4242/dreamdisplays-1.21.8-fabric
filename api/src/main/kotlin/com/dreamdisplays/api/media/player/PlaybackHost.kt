package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode
import java.util.*

/**
 * Display abstraction for media player; platform-side implementation.
 *
 * @since 1.8.x
 */
interface PlaybackHost {
    /** Stable identifier of this display, used for log labels. */
    val uuid: UUID

    /**
     * The display this player plays for, as named in log labels, error and hitch reports. Same as [uuid] except for a
     * player loaded in the background for a display, whose own [uuid] keeps its buffers apart.
     */
    val reportId: UUID get() = uuid

    /** Viewer-selected target quality used to seed stream resolution. */
    val quality: VideoQuality

    /** Current GPU texture width (the pending one during a quality handoff). */
    val textureWidth: Int

    /** Current GPU texture height (the pending one during a quality handoff). */
    val textureHeight: Int

    /** True while the viewer has the display paused. */
    val isPaused: Boolean

    /** Effective playback mode. */
    val effectiveMode: PlaybackMode

    /**
     * False when a non-looping fullscreen mode should stop at the end of the VOD instead of
     * restarting it; true everywhere else.
     */
    val shouldLoopOnEnd: Boolean

    /**
     * True when a server timeline drives this display (Synced, Broadcast): at the end of the stream the
     * player then holds its last frame and leaves what comes next (the next queue entry, the timeline's own loop
     * wrap, a pause) to that timeline, never looping or reporting on its own.
     */
    val followsServerTimeline: Boolean get() = false

    /** Content aspect ratio of the resolved video; the player updates this on (re)resolve. */
    var videoContentAspect: Double

    /** Last fatal/recoverable media error; the player sets this to surface failures to the UI. */
    var mediaError: DreamMediaException?

    /**
     * Seconds of a direct media file to download ahead of playback, or null for the player's default.
     * 0 turns read-ahead off for this display.
     */
    val readAheadSeconds: Int? get() = null

    /** SRT receive latency in ms for a live relay stream without its own, or null for the player's default. */
    val liveLatencyMs: Int? get() = null

    /** Invoked after a seek so the host can react (e.g. clear stale frames). */
    fun afterSeek()

    /** Begins a dual-texture quality handoff (stage a pending texture). */
    fun beginQualityHandoff()

    /** Cancels an in-flight quality handoff and drops the staged texture. */
    fun cancelQualityHandoff()

    /** Recreates the display texture at the current dimensions. */
    fun reloadTexture()

    /** Signals that non-looping VOD playback reached its end at [positionNanos]. */
    fun onPlaybackEnded(positionNanos: Long)
}
