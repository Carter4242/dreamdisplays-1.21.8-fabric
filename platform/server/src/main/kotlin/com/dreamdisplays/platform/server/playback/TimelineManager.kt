package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.queue.DurationReports
import com.dreamdisplays.core.protocol.common.packets.ReportKind
import com.dreamdisplays.core.protocol.common.packets.ReportRequest
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.karaoke.KaraokeTiming
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.toSync
import com.dreamdisplays.core.queue.EndOfStreamGuard
import com.dreamdisplays.core.queue.LoopWraps
import com.dreamdisplays.core.queue.QueueTiming
import com.dreamdisplays.core.sync.DisplayFollowers
import com.dreamdisplays.core.sync.EmptyDisplays
import com.dreamdisplays.core.sync.ModeChanges
import com.dreamdisplays.core.sync.SavedPlayback
import com.dreamdisplays.core.sync.TimelineRecords
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
    private val commandThrottle = ActionThrottle()
    private const val COMMAND_COOLDOWN_MS = 100L
    private lateinit var transport: PlaybackTransport
    private val timelines = ConcurrentHashMap<UUID, Timeline>()

    /** Client-reported media durations of each display's video, and the last settled one per display and URL. */
    private val durations = com.dreamdisplays.core.queue.VideoDurations()
    private val lastBroadcast = ConcurrentHashMap<UUID, Long>()
    private val startHolds = ConcurrentHashMap<UUID, Long>()
    private const val START_HOLD_MS = 5_000L

    /**
     * A loop restart is sent this long before the end at most ([tickEnds] runs every 100 ms): the timeline's end is
     * [QueueTiming.END_SAFETY_MS] past the media's, so viewers get it as their stream ends and start on the end instant.
     */
    private const val LOOP_EARLY_MS = 150L

    /** The play-through each display's timeline was in at the last [tickEnds], with that timeline ([LoopWraps]). */
    private val playThroughs = ConcurrentHashMap<UUID, Pair<Timeline, Long>>()

    /** The restart [tickEnds] last scheduled per display, to tell whether it is still the display's timeline. */
    private val loopRestarts = ConcurrentHashMap<UUID, Timeline>()

    /**
     * Called by [tickEnds] just before a display's video starts over at its end, before the restart is sent (karaoke:
     * a line singer's song is over, so the restart already reaches them without their lead).
     */
    var onLoopRestart: (display: DisplayData) -> Unit = {}

    /** Older clients loop their own player at end of stream by seeking to 0; those reports are not seeks. */
    private val endOfStream = EndOfStreamGuard()

    internal const val MAX_SEEK_MS = 24L * 60 * 60 * 1_000

    /** Hold after a resume of a paused display; the platform points this at `display.resume_hold_seconds`. */
    var resumeHoldMs: () -> Long = { 1_000L }

    /** Hold after a seek or restart; the platform points this at `display.seek_hold_seconds`. */
    var seekHoldMs: () -> Long = { 3_000L }

    /** Pause a playing synced display nobody is near after this long (0 = never); `display.auto_pause_when_empty_seconds`. */
    var autoPauseWhenEmptyMs: () -> Long = { 0L }

    private val emptySince = ConcurrentHashMap<UUID, Long>()

    /** When each display's video last changed (server ms), to tell an old client's in-flight report from a fresh one. */
    private val videoChangedAt = ConcurrentHashMap<UUID, Long>()

    /**
     * Players outside a display's range who still follow its timeline (fullscreen viewers, clients holding it ready
     * for a broadcast); every timeline change reaches them at once, not only through the periodic resend.
     */
    var extraReceivers: (displayId: UUID) -> Collection<UUID> = { emptyList() }

    /**
     * Players with [displayId] pinned to PiP: they get every timeline change at any distance, but are never waited for
     * (a pin can outlive the client's PiP, and such a client never reports readiness).
     */
    var pinReceivers: (displayId: UUID) -> Collection<UUID> = { emptyList() }

    /**
     * Players who get [displayId]'s timeline ahead of everyone, by ms of wall time (karaoke singers and their lead);
     * they are also waited for at that shifted position. Empty for nearly every display, which keeps the broadcast.
     */
    var playerLeadsMs: (displayId: UUID) -> Map<UUID, Long> = { emptyMap() }

    /** The lead in the last timeline each player got on its own: player -> (display, lead ms). */
    private val sentLeads = ConcurrentHashMap<UUID, Pair<UUID, Long>>()

    /** The lead (ms) in the last timeline of [displayId] sent to [playerId] alone, or null when none was. */
    fun lastSentLeadMs(displayId: UUID, playerId: UUID): Long? = sentLeads[playerId]?.takeIf { it.first == displayId }?.second

    /**
     * [sync] of [displayId] as [playerId] gets it: moved by their lead (0 for everyone but karaoke singers). Every
     * timeline sent to one player goes through here.
     */
    fun shiftedFor(displayId: UUID, playerId: UUID, sync: DisplaySync): DisplaySync {
        val lead = playerLeadsMs(displayId)[playerId] ?: 0L
        sentLeads[playerId] = displayId to lead
        return KaraokeTiming.withLead(sync, lead)
    }

    /** Sends [sync] of [displayId], already moved by [leadMs], to [playerId] alone (a karaoke lead change). */
    fun sendShifted(playerId: UUID, displayId: UUID, leadMs: Long, sync: DisplaySync) {
        sentLeads[playerId] = displayId to leadMs
        transport.sendTo(playerId, sync)
    }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Wires the platform transport and seeds timelines for already-loaded displays: those in [saved] (the previous
     * plugin instance's [snapshot]) carry on where they were, the rest start afresh.
     */
    fun init(transport: PlaybackTransport, saved: List<TimelineRecords.Saved> = emptyList()) {
        this.transport = transport
        restore(saved)
        DisplayManager.getDisplays().forEach(::ensureTimeline)
        ReadinessTracker.addListener(::releaseIfReady)
    }

    /** Every shared timeline as it stands now, to hand to the next plugin instance ([init]). */
    fun snapshot(): List<TimelineRecords.Saved> {
        val now = transport.nowMs()
        return timelines.mapNotNull { (displayId, timeline) ->
            val display = DisplayManager.getDisplayData(displayId) ?: return@mapNotNull null
            if (WatchPartyManager.hasSession(displayId)) return@mapNotNull null
            val mediaMs = durations.mediaMsOf(displayId, display.url)
            TimelineRecords.save(displayId, display.url, timeline, startHolds.containsKey(displayId), mediaMs, now)
        }
    }

    /** Puts back the [saved] timelines of displays that still show the same video on a shared clock. */
    private fun restore(saved: List<TimelineRecords.Saved>) {
        if (saved.isEmpty()) return
        val now = transport.nowMs()
        var restored = 0
        for (record in saved) {
            val displayId = runCatching { UUID.fromString(record.displayId) }.getOrNull() ?: continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) continue
            if (display.url != record.url || !EmptyDisplays.hasVideo(display.url)) continue
            timelines[displayId] = TimelineRecords.restore(record, now)
            durations.seed(displayId, display.url, record.mediaMs)
            restored++
        }
        logger.info("Restored {} of {} saved display timeline(s).", restored, saved.size)
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
        // No video: no clock to move; a play starts the queue when something is queued
        if (!EmptyDisplays.hasVideo(display.url)) {
            return EmptyDisplays.playStartsQueue(display.url, action, QueueManager.advancesAtEnd(display)) &&
                    QueueManager.startFromEmpty(display)
        }
        if (action == PlaybackAction.SPEED) return PlaybackPermissions.canChangeSpeed(ctx) && setSpeed(display, rate)

        val now = transport.nowMs()
        val target = if (action == PlaybackAction.SEEK) clampSeek(positionMs, display) else 0L
        when (endOfStream.classify(display.id, timelines[display.id], now, action, target, durationMsOf(display))) {
            EndOfStreamGuard.Verdict.USER_SEEK -> {}
            // A client's stream ended a moment before the shared clock: the queue moves on at the timeline's own
            // end, or the timeline wraps by itself. Never a restart for everyone.
            EndOfStreamGuard.Verdict.STREAM_ENDED -> {
                logger.info("Display {}: a client looped at its end of stream; not taken as a seek.", display.id)
                QueueManager.onClientStreamEnded(display)
                return false
            }
            EndOfStreamGuard.Verdict.DUPLICATE -> return false
        }
        val holding = startHolds.remove(display.id) != null
        val current = timelines[display.id] ?: Timeline.start(now, durationMs = durationMsOf(display), loop = true)
        val updated = when (action) {
            PlaybackAction.PLAY -> current.withPaused(false, now)
            PlaybackAction.PAUSE -> current.withPaused(true, now)
            PlaybackAction.SEEK -> current.seekedTo(target, now)
            // Keeps its length and loop: without them its clock would count past the end and never come round again
            PlaybackAction.RESTART -> Timeline.start(now, durationMs = current.durationMs, loop = true, rate = current.rate)
            PlaybackAction.SPEED -> return false // Handled above
        }
        // A resume, seek or restart parks every viewer paused at the new position for a moment, so they all
        // snap onto it and then start together instead of each correcting its own offset afterwards.
        val resumes = action == PlaybackAction.PLAY && current.paused
        val jumps = action == PlaybackAction.SEEK || action == PlaybackAction.RESTART
        if (resumes || jumps) {
            holdThenRelease(
                display, updated, now,
                // A start hold is a playing display waiting for its viewers: a seek in it plays after, too
                playAfter = resumes || EndOfStreamGuard.playsAfterJump(action, current.paused, holding),
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
        if (!EmptyDisplays.hasVideo(display.url)) return
        val current = timelines[display.id] ?: return
        if (!current.paused) return
        startHolds[display.id] = deadlineMs
        val now = transport.nowMs()
        if (now >= deadlineMs || waitingFor(display, current.positionMs).isEmpty()) {
            release(display, now)
        } else {
            broadcast(display, current)
            askUnreported(display)
        }
    }

    /**
     * A hold waits for viewers that never said whether they play [display] (their report went to a plugin that was
     * reloaded, or was dropped with the previous video): ask them, so the hold ends when they answer instead of at its
     * deadline. Protocol-11 clients drop the request.
     */
    private fun askUnreported(display: DisplayData) {
        val request = ReportRequest(display.id, ReportKind.READINESS)
        ReadinessTracker.unreported(display.id, viewersOf(display)).forEach { transport.sendTo(it, request) }
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
        if (!EmptyDisplays.hasVideo(display.url)) return false
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
        ReadinessTracker.forgetHeldStates(display.id)
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
        transport.sendTo(playerId, shiftedFor(display.id, playerId, syncPacket(display, timeline, transport.nowMs())))
    }

    /** Re-initializes the clock after a base-mode change and broadcasts the result. */
    fun onModeChanged(display: DisplayData, positionMs: Long = -1) {
        // An empty display has no clock in any mode
        val shared = (display.mode == PlaybackMode.SYNCED || display.mode == PlaybackMode.BROADCAST) &&
                EmptyDisplays.hasVideo(display.url)
        // Synced <-> Broadcast run the same clock: it carries on as it is (paused, speed, a start hold)
        val existing = timelines[display.id]?.takeIf { shared }
        if (existing == null) {
            timelines.remove(display.id)
            startHolds.remove(display.id)
        }
        val now = transport.nowMs()
        // The length viewers reported for the video it holds now (in any mode: it may have changed while Local), else
        // the one stored for it (nobody reported since a restart or a watch party)
        val reportedMs = durations.mediaMsOf(display.id, display.url)
        val durationMs = ModeChanges.durationAfter(reportedMs, durationMsOf(display))
        display.duration = durationMs.takeIf { it > 0L }?.let { it * 1_000_000L }
        val timeline = ModeChanges.timelineAfter(existing, shared, positionMs, now, durationMs)
        if (timeline != null) {
            timelines[display.id] = timeline
            broadcast(display, timeline)
            // No reported length: clients loaded the video before it was shared and reported nothing since; ask them
            if (reportedMs == 0L) askNearby(display, ReportKind.DURATION)
        }
    }

    private fun askNearby(display: DisplayData, kind: Int) {
        val request = ReportRequest(display.id, kind)
        transport.nearbyPlayerIds(display).forEach { transport.sendTo(it, request) }
    }

    /**
     * Resets the clock to 0 when the video changes (`SYNCED` / `BROADCAST` only), held paused until every viewer has the
     * new video ready, for [START_HOLD_MS] at most. [readyAtStart]: players already holding the new video ready at 0
     * (the queue's preloads), so a start does not wait for them to say so again; when that is everyone it is instant.
     *
     * [startAtMs] (a queue moving on at the end of the previous video): the new video starts at exactly that wall time,
     * with no hold, when anyone has it preloaded; they cut from the old video's last frame straight to the new one,
     * and anyone not ready joins late.
     *
     * [restore] (a screen share ended): the video the display had before comes back paused where it was, at its speed,
     * with no start hold; someone presses play.
     */
    fun onVideoChanged(
        display: DisplayData, readyAtStart: Collection<UUID> = emptyList(), startAtMs: Long? = null,
        restore: SavedPlayback? = null,
    ) {
        if (!EmptyDisplays.hasVideo(display.url)) return onVideoCleared(display)
        // Fresh video; one this display played before (a screen share ended, a queue replays it) keeps its known length.
        // Tracked in Local too, so a switch back to a shared mode starts from this video's length.
        val knownMs = QueueTiming.scheduledDurationMs(durations.onVideoChanged(display.id, display.url))
        display.duration = knownMs.takeIf { it > 0L }?.let { it * 1_000_000L }
        val now = transport.nowMs()
        videoChangedAt[display.id] = now
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return
        endOfStream.onVideoChanged(display.id, maxOf(now, startAtMs ?: now))
        if (restore != null) {
            startHolds.remove(display.id)
            ReadinessTracker.forgetHeldStates(display.id)
            val restored = restore.restoredTimeline(now, knownMs)
            timelines[display.id] = restored
            broadcast(display, restored)
            return
        }
        // The display keeps its speed across videos
        val speed = timelines[display.id]?.rate ?: PlaybackSpeed.NORMAL
        if (startAtMs != null && readyAtStart.isNotEmpty()) {
            startHolds.remove(display.id)
            ReadinessTracker.forgetHeldStates(display.id)
            readyAtStart.forEach { ReadinessTracker.markReady(display.id, it, 0L) }
            // Anchored at the start instant: until then the wire timeline says "starts in N ms" (DisplaySync.startInMs)
            val scheduled = Timeline(0, maxOf(now, startAtMs), paused = false, durationMs = knownMs, loop = true, rate = speed)
            timelines[display.id] = scheduled
            broadcast(display, scheduled)
            return
        }
        val fresh = Timeline.start(now, paused = true, durationMs = knownMs, loop = true, rate = speed)
        timelines[display.id] = fresh
        // What clients held ready belongs to the old video
        ReadinessTracker.forgetHeldStates(display.id)
        // A singer is waited for at their lead: count their preload there
        val leads = playerLeadsMs(display.id)
        readyAtStart.forEach {
            ReadinessTracker.markReady(display.id, it, KaraokeTiming.heldReadyPositionMs(leads[it] ?: 0L, speed, knownMs))
        }
        startWhenReady(display, now + START_HOLD_MS)
    }

    /**
     * [display]'s video was taken off (its URL is empty now): it has no clock, length, start hold or readiness to wait
     * for, so it never loops, holds or reaches an end until a video is set again.
     */
    fun onVideoCleared(display: DisplayData) {
        durations.onVideoChanged(display.id, display.url)
        display.duration = null
        videoChangedAt[display.id] = transport.nowMs()
        timelines.remove(display.id)
        startHolds.remove(display.id)
        loopRestarts.remove(display.id)
        playThroughs.remove(display.id)
        emptySince.remove(display.id)
        lastBroadcast.remove(display.id)
        endOfStream.forget(display.id)
        ReadinessTracker.forgetHeldStates(display.id)
    }

    /** The authoritative timeline of [displayId], or null when it has none (`LOCAL`, watch party, unknown). */
    fun timelineOf(displayId: UUID): Timeline? = timelines[displayId]

    /** True while [displayId] sits in a start hold (paused, about to start by itself). */
    fun isHolding(displayId: UUID): Boolean = startHolds.containsKey(displayId)

    /**
     * Applies a media duration [senderId]'s client measured for [display]'s video [url] (empty from older clients).
     * Only a report on the display's current video counts, from a viewer in range or a far follower (fullscreen, PiP).
     *
     * [QueueTiming.mediaDurationMs] settles on the largest report (bounded by the playing queue entry's duration, never
     * below it, never shrinking); the timeline runs [QueueTiming.scheduledDurationMs] of it, so its end (and the
     * queue's switch) never comes before the media's.
     */
    fun onDurationReported(display: DisplayData, senderId: UUID, durationMs: Long, url: String = "", mediaGen: Long = 0L) {
        if (!EmptyDisplays.hasVideo(display.url)) return
        val shared = display.mode == PlaybackMode.SYNCED || display.mode == PlaybackMode.BROADCAST
        if (!shared && display.mode != PlaybackMode.LOCAL) return
        val sinceChange = videoChangedAt[display.id]?.let { transport.nowMs() - it } ?: Long.MAX_VALUE
        if (!DurationReports.accepts(durationMs, url, mediaGen, display.url, display.mediaGen, sinceChange)) return
        val nearby = transport.nearbyPlayerIds(display)
        if (!DisplayFollowers.mayReportDuration(senderId, nearby, extraReceivers(display.id), pinReceivers(display.id))) return
        // Report cooldowns start afresh with each video ([VideoDurations])
        val mediaMs = durations.report(
            display.id, display.url, senderId, durationMs, QueueManager.playingEntryDurationMs(display), transport.nowMs(),
        ) ?: return
        val scheduledMs = QueueTiming.scheduledDurationMs(mediaMs)
        display.duration = scheduledMs * 1_000_000L
        // Local: kept for when the display is shared again
        if (!shared) return
        val current = timelines[display.id] ?: return
        timelines[display.id] = current.copy(durationMs = scheduledMs, loop = true)
        broadcast(display, timelines.getValue(display.id))
    }

    /**
     * Restarts every playing display that reaches the end of its video with nothing queued from 0, for everyone,
     * scheduled at the end instant ([QueueTiming.loopRestartAtMs]); clients hold the last frame at end of stream and
     * never loop a shared display themselves. A display whose queue moves on at the end, a watch party or a start hold
     * is left alone; a fullscreen session of it that does not loop ends there. Called every couple of ticks.
     */
    fun tickEnds() {
        if (timelines.isEmpty()) return
        val now = transport.nowMs()
        for ((displayId, timeline) in timelines) {
            if (timeline.paused || startHolds.containsKey(displayId)) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) continue
            if (!EmptyDisplays.hasVideo(display.url)) continue
            val durationMs = knownDurationMs(display, timeline)
            if (wrappedUnseen(display, timeline, now, durationMs)) continue
            val restartAt = QueueTiming.loopRestartAtMs(timeline, now, LOOP_EARLY_MS, durationMs) ?: continue
            if (WatchPartyManager.hasSession(displayId) || QueueManager.advancesAtEnd(display)) continue
            // A fullscreen session that does not loop is over at this end; the display itself starts over as usual
            FullscreenBroadcastManager.endNonLoopingSessions(displayId)
            // Late end-of-stream loops from older clients around this end are not seeks
            endOfStream.onVideoChanged(displayId, restartAt)
            runCatching { onLoopRestart(display) }
                .onFailure { logger.warn("Loop restart hook failed for display {}.", displayId, it) }
            val restarted = Timeline(0, restartAt, paused = false, durationMs = durationMs, loop = true, rate = timeline.rate)
            timelines[displayId] = restarted
            loopRestarts[displayId] = restarted
            broadcast(display, restarted)
            logger.info("Display {}: reached the end of its video; restarted from 0 for everyone.", displayId)
        }
    }

    /**
     * True when [timeline] ran past its end since the last [tickEnds] without being restarted (a main-thread stall longer
     * than the restart's early window): it wrapped by itself, while clients hold their last frame until they hear a
     * timeline. It is sent again at once, where it stands, and that end counts as a restart (karaoke line songs end).
     */
    private fun wrappedUnseen(display: DisplayData, timeline: Timeline, now: Long, durationMs: Long): Boolean {
        val current = LoopWraps.playThrough(timeline, now, durationMs)
        val previous = playThroughs[display.id]?.takeIf { it.first == timeline }?.second
        if (current == null) {
            playThroughs.remove(display.id)
            return false
        }
        playThroughs[display.id] = timeline to current
        if (!LoopWraps.missedWrap(previous, current)) return false
        // The queue moves on by itself after a missed end (advanceDue counts past ends); a party runs its own
        if (WatchPartyManager.hasSession(display.id) || QueueManager.advancesAtEnd(display)) return false
        FullscreenBroadcastManager.endNonLoopingSessions(display.id)
        endOfStream.onVideoChanged(display.id, now)
        runCatching { onLoopRestart(display) }
            .onFailure { logger.warn("Loop restart hook failed for display {}.", display.id, it) }
        val reanchored = LoopWraps.reanchored(timeline, now)
        timelines[display.id] = reanchored
        broadcast(display, reanchored)
        logger.info("Display {}: its video ended during a server stall; sent where it stands after the wrap.", display.id)
        return true
    }

    /**
     * The wall time at which [displayId]'s video starts over after [tickEnds] restarted it at its end, while that
     * restart is still ahead and still its timeline; null otherwise ([QueueTiming.pendingRestartAtMs]).
     */
    fun pendingLoopRestartAt(displayId: UUID): Long? =
        QueueTiming.pendingRestartAtMs(loopRestarts[displayId], timelines[displayId], transport.nowMs())

    /** [display]'s video length to loop at: the timeline's, else the display's stored one, else its queue entry's. */
    private fun knownDurationMs(display: DisplayData, timeline: Timeline): Long =
        timeline.durationMs.takeIf { it > 0L }
            ?: durationMsOf(display).takeIf { it > 0L }
            ?: QueueTiming.scheduledDurationMs(QueueManager.playingEntryDurationMs(display))

    /** Forgets a removed display. */
    fun remove(displayId: UUID) {
        ReadinessTracker.forgetDisplay(displayId)
        endOfStream.forget(displayId)
        emptySince.remove(displayId)
        videoChangedAt.remove(displayId)
        timelines.remove(displayId)
        loopRestarts.remove(displayId)
        playThroughs.remove(displayId)
        durations.forget(displayId)
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
        carryKnownDurations()
        for ((displayId, timeline) in timelines) {
            if (now - (lastBroadcast[displayId] ?: 0L) < PERIODIC_BROADCAST_MS) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            broadcast(display, timeline)
        }
    }

    /**
     * Pauses (server timeline) a playing `SYNCED` display once no player has been in its range (and nobody watched it
     * in fullscreen from afar) for [autoPauseWhenEmptyMs], so a forgotten movie does not run on with nobody watching. It
     * is not resumed automatically; the next viewer presses play.
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
                DisplayFollowers.watched(transport.nearbyPlayerIds(display), extraReceivers(displayId))
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

    /** Backstop: a timeline without a length while its display knows its video's gets it, so it wraps at the end. */
    private fun carryKnownDurations() {
        for ((displayId, timeline) in timelines) {
            if (timeline.durationMs > 0L && timeline.loop) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) continue
            val durationMs = durationMsOf(display).takeIf { it > 0L } ?: continue
            // A clock already past that end is restarted at once by tickEnds instead
            if (QueueTiming.loopRestartAtMs(timeline, transport.nowMs(), 0L, durationMs) != null) continue
            val updated = timeline.copy(durationMs = durationMs, loop = true)
            timelines[displayId] = updated
            broadcast(display, updated)
            logger.info("Display {}: its timeline had lost its video's length; restored it.", displayId)
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
        if (!EmptyDisplays.hasVideo(display.url)) {
            timelines.remove(display.id)
            return null
        }
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
     * The one place a display's wire timeline is built (scheduled starts included: see `Timeline.toSync`); per-player
     * sends move it by their lead through [shiftedFor].
     */
    private fun syncPacket(display: DisplayData, timeline: Timeline, now: Long) = timeline.toSync(display.id, display.mode, now, display.mediaGen)

    private fun broadcast(display: DisplayData, timeline: Timeline) {
        val now = transport.nowMs()
        lastBroadcast[display.id] = now
        val sync = syncPacket(display, timeline, now)
        val leads = playerLeadsMs(display.id)
        if (leads.isEmpty()) {
            transport.broadcast(display, sync)
        } else {
            for (playerId in transport.nearbyPlayerIds(display)) {
                transport.sendTo(playerId, shiftedFor(display.id, playerId, sync))
            }
        }
        val extra = extraReceivers(display.id)
        val pinners = pinReceivers(display.id)
        if (extra.isEmpty() && pinners.isEmpty()) return
        val far = DisplayFollowers.farReceivers(transport.nearbyPlayerIds(display), extra, pinners)
        for (playerId in far) transport.sendTo(playerId, if (leads.isEmpty()) sync else shiftedFor(display.id, playerId, sync))
    }
}
