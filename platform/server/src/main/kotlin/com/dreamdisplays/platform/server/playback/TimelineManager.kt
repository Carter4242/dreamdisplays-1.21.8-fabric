package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.toSync
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the authoritative playback clock for every `SYNCED` and `BROADCAST` display. Clients never report their own
 * position back as truth.
 */
object TimelineManager {
    private const val PERIODIC_BROADCAST_MS = 5_000L
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1_000
    private const val MIN_DURATION_MS = 2_000L
    private val reportDurationThrottle = ActionThrottle()
    private const val REPORT_DURATION_COOLDOWN_MS = 2_000L
    private val commandThrottle = ActionThrottle()
    private const val COMMAND_COOLDOWN_MS = 100L
    private lateinit var transport: PlaybackTransport
    private val timelines = ConcurrentHashMap<UUID, Timeline>()
    private val lastBroadcast = ConcurrentHashMap<UUID, Long>()
    private val startHolds = ConcurrentHashMap<UUID, Long>()
    private const val START_HOLD_MS = 5_000L

    internal const val MAX_SEEK_MS = 24L * 60 * 60 * 1_000

    /** Hold after a resume of a paused display; the platform points this at `display.resume_hold_seconds`. */
    var resumeHoldMs: () -> Long = { 1_000L }

    /** Hold after a seek or restart; the platform points this at `display.seek_hold_seconds`. */
    var seekHoldMs: () -> Long = { 3_000L }

    /** Pause a playing synced display nobody is near after this long (0 = never); `display.auto_pause_when_empty_seconds`. */
    var autoPauseWhenEmptyMs: () -> Long = { 0L }

    private val emptySince = ConcurrentHashMap<UUID, Long>()
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Wires the platform transport and seeds timelines for already-loaded displays. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
        DisplayManager.getDisplays().forEach(::ensureTimeline)
    }

    /**
     * Applies a client playback intent to a `SYNCED` display. Returns true when it was permitted, applied, and rebroadcast;
     * false otherwise. [rate] is the requested speed of a [PlaybackAction.SPEED] intent.
     */
    fun onCommand(
        display: DisplayData, senderId: UUID, action: PlaybackAction, positionMs: Long,
        rate: Double = PlaybackSpeed.NORMAL,
    ): Boolean {
        if (display.mode != PlaybackMode.SYNCED || WatchPartyManager.hasSession(display.id)) return false
        val ctx = PlaybackContexts.of(
            display, senderId, transport.isAdmin(senderId),
            { transport.isTerritoryMember(display, senderId) },
        )
        if (!PlaybackPermissions.canPlayPause(ctx)) return false
        if (!commandThrottle.tryAcquire(senderId, COMMAND_COOLDOWN_MS)) return false
        if (senderId !in transport.nearbyPlayerIds(display)) return false
        if (action == PlaybackAction.SPEED) return PlaybackPermissions.canChangeSpeed(ctx) && setSpeed(display, rate)

        val now = transport.nowMs()
        startHolds.remove(display.id)
        val current = timelines[display.id] ?: Timeline.start(now)
        val updated = when (action) {
            PlaybackAction.PLAY -> current.withPaused(false, now)
            PlaybackAction.PAUSE -> current.withPaused(true, now)
            PlaybackAction.SEEK -> current.seekedTo(clampSeek(positionMs, display), now)
            PlaybackAction.RESTART -> Timeline.start(now, rate = current.rate)
            PlaybackAction.SPEED -> return false // Handled above
        }
        // A resume, seek or restart parks every viewer paused at the new position for a moment, so they all
        // snap onto it and then start together instead of each correcting its own offset afterwards.
        val resumes = action == PlaybackAction.PLAY && current.paused
        val jumps = action == PlaybackAction.SEEK || action == PlaybackAction.RESTART
        if (resumes || jumps) {
            holdThenRelease(
                display, updated, now,
                playAfter = resumes || action == PlaybackAction.RESTART || !current.paused,
                holdMs = if (resumes) resumeHoldMs() else seekHoldMs(),
            )
            return true
        }
        timelines[display.id] = updated
        broadcast(display, updated)
        return true
    }

    /**
     * Start hold: park the timeline paused at [target] and, when [playAfter], release it after [holdMs] through the
     * start-hold tick. Clients snap their paused player exactly onto a paused timeline, so during the hold every
     * viewer lines up on the same frame and the release starts them together. A zero hold plays at once and
     * leaves any small offsets to the clients' drift correction.
     */
    private fun holdThenRelease(display: DisplayData, target: Timeline, now: Long, playAfter: Boolean, holdMs: Long) {
        if (playAfter && holdMs <= 0L) {
            val playing = target.withPaused(false, now)
            timelines[display.id] = playing
            broadcast(display, playing)
            return
        }
        val held = target.withPaused(true, now)
        timelines[display.id] = held
        if (playAfter) startHolds[display.id] = now + holdMs
        broadcast(display, held)
    }

    /**
     * Server-initiated play / pause for [display], bypassing the sender / nearby checks [onCommand]
     * applies to client intents — used by [ScheduledPlaybackManager] when a scheduled action fires.
     */
    fun applyScheduled(display: DisplayData, action: PlaybackAction): Boolean {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return false
        if (action != PlaybackAction.PLAY && action != PlaybackAction.PAUSE) return false
        val now = transport.nowMs()
        startHolds.remove(display.id)
        val current = ensureTimeline(display) ?: Timeline.start(now, durationMs = durationMsOf(display), loop = true)
        val updated = current.withPaused(action == PlaybackAction.PAUSE, now)
        timelines[display.id] = updated
        broadcast(display, updated)
        return true
    }

    /**
     * Sets the playback speed of a `SYNCED` / `BROADCAST` display's timeline to [rate] (clamped to
     * [PlaybackSpeed.MIN]..[PlaybackSpeed.MAX]) without moving its position; permission checks are the caller's.
     * A playing timeline is held at the current position for [seekHoldMs] first, like a seek: every viewer
     * restarts its player at the new speed there, then all start together. Returns false when the display has no
     * shared timeline.
     */
    fun setSpeed(display: DisplayData, rate: Double): Boolean {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return false
        val current = ensureTimeline(display) ?: return false
        val speed = PlaybackSpeed.clamp(rate)
        if (speed == current.rate) return true
        val now = transport.nowMs()
        val updated = current.withRate(speed, now)
        // A paused timeline (including one in a start hold) just takes the new speed and keeps its hold
        if (current.paused) {
            timelines[display.id] = updated
            broadcast(display, updated)
            return true
        }
        startHolds.remove(display.id)
        holdThenRelease(display, updated, now, playAfter = true, holdMs = seekHoldMs())
        return true
    }

    /** The playback speed of [display]'s shared timeline (1 when it has none). */
    fun speedOf(display: DisplayData): Double = timelines[display.id]?.rate ?: PlaybackSpeed.NORMAL

    /** Sends the current timeline to one player (RequestSync reply / late-join catch-up). */
    fun sendCurrent(display: DisplayData, playerId: UUID) {
        val timeline = ensureTimeline(display) ?: return
        transport.sendTo(playerId, timeline.toSync(display.id, display.mode, transport.nowMs()))
    }

    /** Re-initializes the clock after a base-mode change and broadcasts the result. */
    fun onModeChanged(display: DisplayData, positionMs: Long = -1) {
        timelines.remove(display.id)
        startHolds.remove(display.id)
        val now = transport.nowMs()
        val durationMs = durationMsOf(display)
        val timeline = when (display.mode) {
            PlaybackMode.SYNCED, PlaybackMode.BROADCAST ->
                Timeline(positionMs.coerceAtLeast(0), now, paused = false, durationMs = durationMs, loop = true)

            else -> null
        }
        if (timeline != null) {
            timelines[display.id] = timeline
            broadcast(display, timeline)
        }
    }

    /** Resets the clock to 0 when the video changes (`SYNCED` / `BROADCAST` only), held paused for [START_HOLD_MS]. */
    fun onVideoChanged(display: DisplayData) {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return
        display.duration = null // Fresh video
        val now = transport.nowMs()
        // The display keeps its speed across videos
        val speed = timelines[display.id]?.rate ?: PlaybackSpeed.NORMAL
        val fresh = Timeline.start(now, paused = true, loop = true, rate = speed)
        timelines[display.id] = fresh
        startHolds[display.id] = now + START_HOLD_MS
        broadcast(display, fresh)
    }

    /**
     * Applies a client-reported media duration for [display]'s current video.
     *
     * First-report-wins: a no-op once [DisplayData.duration] is already set.
     */
    fun onDurationReported(display: DisplayData, senderId: UUID, durationMs: Long) {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) return
        if ((display.duration ?: 0L) > 0L) return
        if (senderId !in transport.nearbyPlayerIds(display)) return
        if (!reportDurationThrottle.tryAcquire(display.id, REPORT_DURATION_COOLDOWN_MS)) return

        display.duration = durationMs * 1_000_000L
        val current = timelines[display.id] ?: return
        timelines[display.id] = current.copy(durationMs = durationMs, loop = true)
        broadcast(display, timelines.getValue(display.id))
    }

    /** Forgets a removed display. */
    fun remove(displayId: UUID) {
        emptySince.remove(displayId)
        timelines.remove(displayId)
        lastBroadcast.remove(displayId)
        startHolds.remove(displayId)
    }

    /**
     * Periodic keep-alive so late joiners and drifting clients stay corrected.
     *
     * Called once per second.
     */
    fun tick() {
        if (timelines.isEmpty()) return
        val now = transport.nowMs()
        releaseStartHolds(now)
        autoPauseEmpty(now)
        for ((displayId, timeline) in timelines) {
            if (now - (lastBroadcast[displayId] ?: 0L) < PERIODIC_BROADCAST_MS) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            broadcast(display, timeline)
        }
    }

    /**
     * Pauses (server timeline) a playing `SYNCED` display once no player has been in its range for
     * [autoPauseWhenEmptyMs], so a forgotten movie does not run on with nobody watching. It is not resumed
     * automatically; the next viewer presses play.
     */
    private fun autoPauseEmpty(now: Long) {
        val limit = autoPauseWhenEmptyMs()
        if (limit <= 0L) {
            emptySince.clear()
            return
        }
        for ((displayId, timeline) in timelines) {
            if (timeline.paused || startHolds.containsKey(displayId)) {
                emptySince.remove(displayId)
                continue
            }
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (display.mode != PlaybackMode.SYNCED || WatchPartyManager.hasSession(displayId) ||
                transport.nearbyPlayerIds(display).isNotEmpty()
            ) {
                emptySince.remove(displayId)
                continue
            }
            val since = emptySince.getOrPut(displayId) { now }
            if (now - since < limit) continue
            emptySince.remove(displayId)
            val paused = timeline.withPaused(true, now)
            timelines[displayId] = paused
            broadcast(display, paused)
            logger.info("Paused display {}: nobody has been near it for {} s.", displayId, limit / 1_000)
        }
    }

    private fun releaseStartHolds(now: Long) {
        for ((displayId, startAt) in startHolds) {
            if (now < startAt) continue
            startHolds.remove(displayId)
            if (WatchPartyManager.hasSession(displayId)) continue
            val timeline = timelines[displayId] ?: continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            val started = timeline.withPaused(false, now)
            timelines[displayId] = started
            broadcast(display, started)
        }
    }

    internal fun clampSeek(positionMs: Long, display: DisplayData): Long {
        val durationMs = durationMsOf(display)
        return positionMs.coerceIn(0, if (durationMs > 0) durationMs else MAX_SEEK_MS)
    }

    private fun durationMsOf(display: DisplayData): Long = display.duration?.let { it / 1_000_000L } ?: 0L

    private fun ensureTimeline(display: DisplayData): Timeline? {
        if (WatchPartyManager.hasSession(display.id)) return null
        val durationMs = durationMsOf(display)
        return when (display.mode) {
            PlaybackMode.SYNCED, PlaybackMode.BROADCAST ->
                timelines.getOrPut(display.id) {
                    Timeline.start(
                        transport.nowMs(),
                        durationMs = durationMs,
                        loop = true
                    )
                }

            else -> {
                timelines.remove(display.id); null
            }
        }
    }

    private fun broadcast(display: DisplayData, timeline: Timeline) {
        val now = transport.nowMs()
        lastBroadcast[display.id] = now
        transport.broadcast(display, timeline.toSync(display.id, display.mode, now))
    }
}
