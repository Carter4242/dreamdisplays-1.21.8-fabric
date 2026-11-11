package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.karaoke.KaraokeTiming
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

    /**
     * Players outside a display's range who still follow its timeline (fullscreen viewers, clients holding it ready
     * for a broadcast); every timeline change reaches them at once, not only through the periodic resend.
     */
    var extraReceivers: (displayId: UUID) -> Collection<UUID> = { emptyList() }

    /**
     * Players who get [displayId]'s timeline ahead of everyone, by ms of wall time (karaoke singers and their lead);
     * they are also waited for at that shifted position. Empty for nearly every display, which keeps the broadcast.
     */
    var playerLeadsMs: (displayId: UUID) -> Map<UUID, Long> = { emptyMap() }
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Wires the platform transport and seeds timelines for already-loaded displays. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
        DisplayManager.getDisplays().forEach(::ensureTimeline)
        ReadinessTracker.addListener(::releaseIfReady)
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
     * Start hold: park the timeline paused at [target] and, when [playAfter], start it once every viewer holds it ready
     * there ([startWhenReady]), or after [holdMs] at the latest. Clients snap their paused player exactly onto a
     * paused timeline and keep it open on that frame, so the start is instant when they were ready already (a paused
     * display) and as quick as the slowest viewer's buffering otherwise. A zero hold plays at once.
     */
    private fun holdThenRelease(display: DisplayData, target: Timeline, now: Long, playAfter: Boolean, holdMs: Long) {
        val held = target.withPaused(true, now)
        timelines[display.id] = held
        if (!playAfter) {
            broadcast(display, held)
            return
        }
        startWhenReady(display, if (holdMs <= 0L) now else now + holdMs)
    }

    /**
     * Starts [display] from where its paused timeline stands as soon as every viewer holds it ready there (instantly if
     * they already do), and at [deadlineMs] at the latest. The start is scheduled [ReadinessTracker.leadMs] ahead so
     * all viewers begin on the same frame. The display must be paused (a hold); a playing one is left alone.
     *
     * This is the call for anything that wants a display to start on time: a resume, seek or new video here, a
     * fullscreen start, a queue moving to its next item.
     */
    fun startWhenReady(display: DisplayData, deadlineMs: Long) {
        val current = timelines[display.id] ?: return
        if (!current.paused) return
        startHolds[display.id] = deadlineMs
        val now = transport.nowMs()
        if (now >= deadlineMs || waitingFor(display, current.positionMs).isEmpty()) {
            release(display, now)
        } else {
            broadcast(display, current)
        }
    }

    /** The viewers a start of [display] at [positionMs] still waits for (empty = it can start now). */
    fun waitingFor(display: DisplayData, positionMs: Long): List<UUID> {
        val viewers = viewersOf(display)
        val leads = playerLeadsMs(display.id)
        if (leads.isEmpty()) return ReadinessTracker.waitingFor(display.id, positionMs, viewers)
        val timeline = timelines[display.id]
        return viewers.groupBy { leads[it] ?: 0L }.flatMap { (lead, players) ->
            val at = if (lead == 0L || timeline == null) positionMs
            else KaraokeTiming.leadPosition(positionMs, lead, timeline.rate, timeline.durationMs, timeline.loop)
            ReadinessTracker.waitingFor(display.id, at, players)
        }
    }

    private fun viewersOf(display: DisplayData): Collection<UUID> =
        transport.nearbyPlayerIds(display) + extraReceivers(display.id)

    /** A client reported on [displayId]: start its hold now if that was the last viewer it waited for. */
    private fun releaseIfReady(displayId: UUID) {
        if (!startHolds.containsKey(displayId)) return
        val display = DisplayManager.getDisplayData(displayId) ?: return
        val current = timelines[displayId] ?: return
        if (!current.paused) return
        if (waitingFor(display, current.positionMs).isEmpty()) release(display, transport.nowMs())
    }

    /** Ends [display]'s hold: plays from its paused position, anchored [ReadinessTracker.leadMs] ahead of [now]. */
    private fun release(display: DisplayData, now: Long) {
        startHolds.remove(display.id)
        val current = timelines[display.id] ?: return
        val lead = ReadinessTracker.leadMs(display.id, viewersOf(display))
        val playing = current.anchoredAt(now).copy(paused = false, serverTimeMs = now + lead)
        timelines[display.id] = playing
        broadcast(display, playing)
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
     * Plays [display] from where its timeline stands, at once: no resume hold, and any pending start hold is released
     * now. Used when a broadcast starts on clients already holding the display ready, where a hold would only add
     * delay. Returns true if the timeline was paused or held and now plays.
     */
    fun playNow(display: DisplayData): Boolean {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return false
        if (WatchPartyManager.hasSession(display.id)) return false
        val current = timelines[display.id] ?: return false
        if (!current.paused) return false
        release(display, transport.nowMs())
        return true
    }

    /**
     * Pauses [display] at the start of its video (cancelling any start hold), so clients can load and hold it on its
     * first frame ahead of a broadcast. Returns false for displays without a server timeline.
     */
    fun parkAtStart(display: DisplayData): Boolean {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return false
        if (WatchPartyManager.hasSession(display.id)) return false
        val now = transport.nowMs()
        startHolds.remove(display.id)
        val current = ensureTimeline(display) ?: return false
        val parked = current.seekedTo(0L, now).withPaused(true, now)
        timelines[display.id] = parked
        broadcast(display, parked)
        return true
    }

    /** True when [display]'s server timeline is paused (or held before starting). */
    fun isPaused(display: DisplayData): Boolean =
        startHolds.containsKey(display.id) || timelines[display.id]?.paused == true

    /**
     * Sets the playback speed of a `SYNCED` / `BROADCAST` display's timeline to [rate] (clamped to
     * [PlaybackSpeed.MIN]..[PlaybackSpeed.MAX]) without moving its position; permission checks are the caller's.
     * A playing timeline is held at the current position like a seek: every viewer restarts its player at the new
     * speed there, and all start together once they are ready ([startWhenReady]), after [seekHoldMs] at the latest. Returns false when the display has no
     * shared timeline.
     */
    fun setSpeed(display: DisplayData, rate: Double): Boolean {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return false
        val current = ensureTimeline(display) ?: return false
        val speed = PlaybackSpeed.clamp(rate)
        if (speed == current.rate) return true
        val now = transport.nowMs()
        val updated = current.withRate(speed, now)
        // Clients held ready at the old speed restart their streams at the new one: wait for them afresh
        ReadinessTracker.forgetDisplay(display.id)
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
    /** Where [display]'s shared clock is now, or null when it has none (Local). */
    fun positionOf(display: DisplayData): Long? = timelines[display.id]?.positionAt(transport.nowMs())

    fun speedOf(display: DisplayData): Double = timelines[display.id]?.rate ?: PlaybackSpeed.NORMAL

    /** Sends the current timeline to one player (RequestSync reply / late-join catch-up). */
    fun sendCurrent(display: DisplayData, playerId: UUID) {
        val timeline = ensureTimeline(display) ?: return
        transport.sendTo(playerId, syncPacket(display, timeline, transport.nowMs(), playerLeadsMs(display.id)[playerId]))
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

    /**
     * Resets the clock to 0 when the video changes (`SYNCED` / `BROADCAST` only), held paused until every viewer has the
     * new video ready, for [START_HOLD_MS] at most. [readyAtStart]: players already holding the new video ready at 0
     * (the queue's preloads), so a start does not wait for them to say so again; when that is everyone it is instant.
     */
    fun onVideoChanged(display: DisplayData, readyAtStart: Collection<UUID> = emptyList()) {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return
        display.duration = null // Fresh video
        val now = transport.nowMs()
        // The display keeps its speed across videos
        val speed = timelines[display.id]?.rate ?: PlaybackSpeed.NORMAL
        val fresh = Timeline.start(now, paused = true, loop = true, rate = speed)
        timelines[display.id] = fresh
        // What clients held ready belongs to the old video
        ReadinessTracker.forgetDisplay(display.id)
        readyAtStart.forEach { ReadinessTracker.markReady(display.id, it, 0L) }
        startWhenReady(display, now + START_HOLD_MS)
    }

    /** The authoritative timeline of [displayId], or null when it has none (`LOCAL`, watch party, unknown). */
    fun timelineOf(displayId: UUID): Timeline? = timelines[displayId]

    /** True while [displayId] sits in a start hold (paused, about to start by itself). */
    fun isHolding(displayId: UUID): Boolean = startHolds.containsKey(displayId)

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
        ReadinessTracker.forgetDisplay(displayId)
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
            if (WatchPartyManager.hasSession(displayId)) {
                startHolds.remove(displayId)
                continue
            }
            val display = DisplayManager.getDisplayData(displayId)
            if (display == null || timelines[displayId] == null) {
                startHolds.remove(displayId)
                continue
            }
            release(display, now)
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

    /**
     * The one place a display's wire timeline is built (scheduled starts included: see `Timeline.toSync`), moved
     * [leadMs] ahead for a player who gets it early (a karaoke singer, see [playerLeadsMs]).
     */
    private fun syncPacket(display: DisplayData, timeline: Timeline, now: Long, leadMs: Long? = null) =
        timeline.toSync(display.id, display.mode, now).let { if (leadMs == null) it else KaraokeTiming.withLead(it, leadMs) }

    private fun broadcast(display: DisplayData, timeline: Timeline) {
        val now = transport.nowMs()
        lastBroadcast[display.id] = now
        val sync = syncPacket(display, timeline, now)
        val leads = playerLeadsMs(display.id)
        if (leads.isEmpty()) {
            transport.broadcast(display, sync)
        } else {
            for (playerId in transport.nearbyPlayerIds(display)) {
                transport.sendTo(playerId, leads[playerId]?.let { syncPacket(display, timeline, now, it) } ?: sync)
            }
        }
        val extra = extraReceivers(display.id)
        if (extra.isEmpty()) return
        val nearby = transport.nearbyPlayerIds(display).toSet()
        for (playerId in extra) {
            if (playerId !in nearby) transport.sendTo(playerId, leads[playerId]?.let { syncPacket(display, timeline, now, it) } ?: sync)
        }
    }
}
