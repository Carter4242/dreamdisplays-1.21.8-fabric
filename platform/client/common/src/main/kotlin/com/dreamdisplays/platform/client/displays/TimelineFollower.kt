package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.sync.DriftController
import com.dreamdisplays.core.sync.SeekLeadLearner
import com.dreamdisplays.core.sync.TimelineEstimator
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Keeps this display on the server-authoritative timeline (Synced / Broadcast / watch party).
 *
 * Packets ([apply]) feed a [TimelineEstimator] (latency-compensated, late-packet tolerant); every client tick
 * ([tick]) projects the server position, compares it with what is actually being heard and shown, and lets a
 * [DriftController] decide: nothing inside the dead band, a slight playback-rate change for small drift, a seek
 * for large drift. While the server is paused, the paused player is snapped exactly onto the server position, so
 * a server-side start hold lines every client up before they resume together.
 *
 * Seeks and cold starts land late by the time the stream takes to restart; that time is learned per kind
 * ([seekLeadMs], [coldLeadMs]) from where previous ones landed and added to the next one.
 *
 * Off normal speed the server position runs at the timeline's rate; positions stay in content ms, while drift is
 * judged in real-time ms (content drift / rate: how far apart viewers are in what they see now), so the dead band,
 * seek threshold and learned restart leads mean the same at every speed.
 */
internal class TimelineFollower(private val screen: DisplayScreen) {
    private val lock = Any()
    private val estimator = TimelineEstimator()
    private val controller = DriftController()

    private var lastServerTimeMs = Long.MIN_VALUE
    private var nextSeq = 0L
    private var pending: Pending? = null
    private var loop = false
    private var latencyCompMs = 0.0

    private var landing: LeadKind? = null
    private var landingRunningSinceNanos = 0L
    private var lastSnapNanos = 0L
    private var coldAfterSnap = false

    /** The player is a preloaded queue item held ready on its first frame: tolerate a small paused offset. */
    private var warmAdopted = false

    /** Karaoke lead in the last timeline (0 = not singing here). */
    private var karaokeLeadMs = 0

    /** The karaoke lead changed while playing: jump to the new position at once instead of drifting there. */
    private var jumpToLead = false

    private var appliedRatio = 1.0
    private var lastRatioPushNanos = 0L
    private var lastDriftMs = 0.0
    private var intervalAction = Action.NONE
    private var lastLogNanos = 0L
    private var reportAction = Action.NONE
    private var reportSeeks = 0

    /**
     * What a landing follows: a seek that restarts the stream ([SEEK]), an in-place seek on an in-process decoder
     * ([QUICK_SEEK]), a resume that restarts the stream ([COLD]), or a resume of a session held open ([WARM], nothing to
     * learn: it starts where it stood). Each restart kind learns its own lead.
     */
    private enum class LeadKind { SEEK, QUICK_SEEK, COLD, WARM }

    /** The next landing that is off may be corrected by one seek at once, outside the controller's cooldown. */
    private var correctAfterLanding = false

    /** The pending landing follows a corrective seek: if it misses again, the controller's cooldown takes over. */
    private var landingIsCorrection = false

    /** Lead (real ms) the pending landing's seek / start was aimed with. */
    private var landingLeadMs = 0L

    /** Longest game stall (ms) around the pending landing: such a landing is re-aimed, never learned from. */
    private var landingStallMs = 0.0
    private var lastTickNanos = 0L
    private var lastStallEndNanos = 0L
    private var lastStallMs = 0.0

    /** What the follower did, in order of significance for the periodic log line. */
    private enum class Action(val label: String) { NONE("none"), RATE("rate"), SNAP("snap"), SEEK("seek") }

    private data class Pending(
        val seq: Long,
        val targetMs: Long,
        val serverTimeMs: Long,
        val paused: Boolean,
        val loop: Boolean,
        val receivedAtNanos: Long,
        val latencyCompMs: Double,
        val rate: Double,
    )

    /** Clears follow state when a new video loads. */
    fun reset() {
        scheduledStart?.cancel(false)
        scheduledStart = null
        synchronized(lock) {
            lastServerTimeMs = Long.MIN_VALUE
            pending = null
            estimator.reset()
            controller.reset()
            landing = null
            landingRunningSinceNanos = 0L
            landingIsCorrection = false
            lastTickNanos = 0L
            lastStallEndNanos = 0L
            lastSnapNanos = 0L
            coldAfterSnap = false
            warmAdopted = false
            jumpToLead = false
            // The new player starts at rate 1; only our bookkeeping needs to follow
            appliedRatio = 1.0
            intervalAction = Action.NONE
        }
    }

    /**
     * The new player is a preloaded queue item held ready on its first frame (see `QueuePreloader`). Like a display
     * prepared for a broadcast, it tolerates a small paused offset instead of snapping and dropping its warm stream.
     */
    fun onWarmAdopted() {
        synchronized(lock) { warmAdopted = true }
    }

    /** Re-applies a timeline packet that arrived before the media player existed or finished init. */
    fun onPlayerCreated() {
        synchronized(lock) { pending }?.let(::applyPending)
    }

    /**
     * Takes the server's [targetMs] / [paused] for the current media, wrapping by the locally known duration when
     * [loop] (Broadcast). Pause state is matched right away; position is corrected here and on every [tick].
     */
    fun apply(
        targetMs: Long, serverTimeMs: Long, paused: Boolean, loop: Boolean, rate: Double = 1.0, startInMs: Int = 0,
        leadMs: Int = 0,
    ) {
        // Any newer word from the server replaces a start still waiting for its moment
        scheduledStart?.cancel(false)
        scheduledStart = null
        synchronized(lock) {
            // A singer's lead changed (they started or stopped singing): a jump of that size is not drift to close
            // slowly at a percent of speed, so the next evaluation seeks there in place at once (one seek, no pause)
            if (leadMs != karaokeLeadMs && !paused && abs(leadMs - karaokeLeadMs) >= LEAD_JUMP_MIN_MS) jumpToLead = true
            karaokeLeadMs = leadMs
        }
        if (paused || startInMs <= 0) {
            applyNow(targetMs, serverTimeMs, paused, loop, if (paused) 0.0 else oneWayLatencyMs(), rate)
            return
        }
        // A start scheduled ahead: the packet left the server startInMs before the start and took one-way latency to
        // get here. Hold paused on the start frame until then, so every viewer begins on it together.
        val owl = oneWayLatencyMs()
        val waitMs = startInMs - owl
        if (waitMs < MIN_SCHEDULE_MS) {
            // Too late to wait: play from where the start has got to by now
            applyNow(targetMs, serverTimeMs, false, loop, -waitMs.coerceAtMost(0.0), rate)
            return
        }
        applyNow(targetMs, serverTimeMs, true, loop, 0.0, rate)
        val startServerTimeMs = serverTimeMs + startInMs
        scheduledStart = START_SCHEDULER.schedule({
            // The player is told on the client thread like every other packet: at most a frame later
            Minecraft.getInstance().execute {
                applyNow(targetMs, startServerTimeMs, false, loop, 0.0, rate)
            }
        }, (waitMs * 1_000.0).toLong(), java.util.concurrent.TimeUnit.MICROSECONDS)
    }

    @Volatile
    private var scheduledStart: java.util.concurrent.ScheduledFuture<*>? = null

    private fun applyNow(targetMs: Long, serverTimeMs: Long, paused: Boolean, loop: Boolean, comp: Double, rate: Double) {
        val packet = synchronized(lock) {
            if (serverTimeMs in 1..<lastServerTimeMs) return
            if (serverTimeMs > 0L) lastServerTimeMs = serverTimeMs
            val now = System.nanoTime()
            latencyCompMs = comp
            this.loop = loop
            if (estimator.onPacket(targetMs, paused, now / NANOS_PER_MS, comp, rate)) controller.resetMeasurement()
            Pending(++nextSeq, targetMs, serverTimeMs, paused, loop, now, comp, rate).also { pending = it }
        }
        applyPending(packet)
    }

    private fun applyPending(packet: Pending) {
        val startMs = projectTargetMs(packet) + if (packet.paused) 0L else contentMs(coldLeadMs, packet.rate)
        screen.primeTimelineStart(wrapForStart(startMs, packet.loop) * 1_000_000L)
        val generation = screen.mediaGeneration
        screen.waitForMFInit {
            synchronized(lock) {
                if (generation != screen.mediaGeneration || pending?.seq != packet.seq) return@waitForMFInit

                val initialTimeline = screen.isWaitingForInitialTimeline
                if (!screen.videoStarted) {
                    screen.beginPlaybackPaused(packet.paused)
                    if (!packet.paused) expectLanding(LeadKind.COLD)
                }

                // Pause-state matching is cheap and is never gated by any cooldown
                if (packet.paused && !screen.isPaused) screen.applyServerPaused(true)
                if (!packet.paused && screen.isPaused) resumeAligned()

                if (screen.isLive || !screen.canSeek()) {
                    screen.markInitialTimelineReady()
                    pending = null
                    return@waitForMFInit
                }
                // While playing, a not-running clock means the stream is (re)starting; its position is
                // meaningless, so let the tick loop measure once it runs.
                if (!packet.paused && !screen.isClockRunning() && !initialTimeline) return@waitForMFInit

                evaluate(System.nanoTime())
                screen.markInitialTimelineReady()
                pending = null
            }
        }
    }

    /** Where the server holds this display paused, in ns of media time, or null when it is not paused (or unknown). */
    fun pausedTargetNanos(): Long? {
        synchronized(lock) {
            if (!estimator.hasTimeline || !estimator.paused) return null
            val durationMs = screen.mediaPlayerDurationNanos / 1_000_000L
            val target = estimator.targetMs(System.nanoTime() / NANOS_PER_MS).roundToLong()
            return clampSeek(target, loop, durationMs) * 1_000_000L
        }
    }

    /**
     * The paused player is being re-opened warm at the server position ([DisplayScreen.holdReadyForBroadcast]), so an
     * earlier paused snap no longer means the next resume starts cold.
     */
    fun onHeldReady() {
        synchronized(lock) { coldAfterSnap = false }
    }

    /** Called every client tick for a loaded display: measures drift and corrects it. */
    fun tick() {
        synchronized(lock) {
            noteTickGap(System.nanoTime())
            if (!screen.followsServerTimeline) {
                pushRatio(1.0, System.nanoTime())
                return
            }
            evaluate(System.nanoTime())
            logIfDue(System.nanoTime())
        }
    }

    /** A long gap between ticks is a game stall: timeline packets queued behind it arrive late and stale. */
    private fun noteTickGap(now: Long) {
        val gapNanos = if (lastTickNanos == 0L) 0L else now - lastTickNanos
        lastTickNanos = now
        if (gapNanos <= STALL_NANOS) return
        lastStallEndNanos = now
        lastStallMs = gapNanos / NANOS_PER_MS
        if (landing != null) landingStallMs = maxOf(landingStallMs, lastStallMs)
    }

    private fun recentlyStalled(now: Long, windowNanos: Long, minStallMs: Double = 0.0): Boolean =
        lastStallEndNanos != 0L && now - lastStallEndNanos < windowNanos && lastStallMs >= minStallMs

    private fun evaluate(now: Long) {
        if (!estimator.hasTimeline || !screen.videoStarted) return
        if (screen.isLive) {
            pushRatio(1.0, now) // Live streams never get position correction
            return
        }
        if (!screen.canSeek()) return
        val settings = screen.syncSettings
        controller.settings = settings
        val durationMs = screen.mediaPlayerDurationNanos / 1_000_000L
        val target = wrap(estimator.targetMs(now / NANOS_PER_MS).roundToLong(), loop, durationMs)
        val localMs = screen.syncPositionNanos / 1_000_000L
        val contentDrift = signedDrift(target - localMs, loop, durationMs)
        // drift > 0: this client is behind the server; < 0: ahead. Judged in real time, so a given content gap
        // counts for less at high speed (it passes sooner) and more at low speed.
        val drift = estimator.toRealMs(contentDrift.toDouble()).roundToLong()
        lastDriftMs = drift.toDouble()

        if (estimator.paused) {
            pushRatio(1.0, now)
            controller.resetMeasurement()
            landing = null
            if (!screen.isPaused) return // The pause itself is still being applied
            // Being opened warm at the server position for a broadcast: a snap now would only close it again
            if (screen.isWarmingUp) return
            // A display held ready (prepared, held by DisplayWarmup, or a queue preload just adopted) tolerates a little
            // offset rather than drop its open stream
            val held = screen.isPrepared || screen.isHeldReady || warmAdopted
            val tolerance = if (held) maxOf(settings.deadBandMs, PREPARED_SNAP_TOLERANCE_MS) else settings.deadBandMs
            if (abs(drift) > tolerance && now - lastSnapNanos >= SNAP_COOLDOWN_NANOS) {
                screen.seekVideoTo(target * 1_000_000L)
                lastSnapNanos = now
                coldAfterSnap = true
                note(Action.SNAP)
            }
            return
        }
        if (screen.isPaused) return // Resume is still being applied
        if (screen.isHeldAtEnd) {
            // This client's stream ended before the shared clock: hold the last frame until the timeline wraps or is
            // moved back (a queue's next entry replaces the player instead)
            controller.resetMeasurement()
            if (!screen.isQueueSwitchPending && durationMs > 0L && target < durationMs - END_HOLD_MS &&
                now - lastSnapNanos >= SNAP_COOLDOWN_NANOS
            ) {
                lastSnapNanos = now
                val startMs = target + contentMs(coldLeadMs, estimator.rate)
                screen.seekVideoTo(clampSeek(startMs, loop, durationMs) * 1_000_000L)
                expectLanding(LeadKind.COLD)
                correctAfterLanding = true
                note(Action.SEEK)
            }
            return
        }
        if (!screen.isClockRunning()) {
            // Restarting after a seek / resume: nothing to measure yet
            controller.resetMeasurement()
            landingRunningSinceNanos = 0L
            return
        }

        if (jumpToLead) {
            jumpToLead = false
            if (abs(drift) > settings.deadBandMs) {
                logVerbose("[dd-sync] {} karaoke lead now {}ms: jumping {}ms", shortId(), karaokeLeadMs, signed(drift.toDouble()))
                seekPlaying(target, durationMs, now)
                return
            }
        }

        landing?.let { kind ->
            if (landingRunningSinceNanos == 0L) landingRunningSinceNanos = now
            val filtered = controller.observe(drift.toDouble(), now / NANOS_PER_MS)
            val settle = if (kind == LeadKind.WARM || kind == LeadKind.QUICK_SEEK) QUICK_SETTLE_NANOS else LANDING_SETTLE_NANOS
            // Out of a long stall, wait for queued packets to bring the target up to date before judging the landing
            if (now - landingRunningSinceNanos < settle || recentlyStalled(now, STALL_SETTLE_NANOS, LONG_STALL_MS)) {
                pushRatio(1.0, now)
                return
            }
            landing = null
            val wasCorrection = landingIsCorrection
            landingIsCorrection = false
            // A consistent far-off landing (a reopen seek costing 1.8 s) is the true restart cost and is learned once
            // confirmed; one around a game stall or tens of seconds off (stale target, other video) never is
            val newTarget = SeekLeadLearner.isNewTarget(filtered)
            if (kind != LeadKind.WARM) learnLead(kind, filtered, wasCorrection)
            // Landed off after a start or resume: one seek fixes it in a frame or two where rate correction at 1 %
            // would take a minute. Any landing far off is re-aimed at once from the fresh target, outside the
            // controller's cooldown and streak, aimed with the cost this landing showed. Only once per episode: the
            // re-aim's landing is accepted and left to rate correction and the controller's cooldown.
            val startFix = correctAfterLanding && abs(filtered) > POST_START_SEEK_MS && screen.seeksCheaply
            val reaim = controller.reaimsLanding(filtered, wasCorrection)
            correctAfterLanding = false
            if (!wasCorrection && (startFix || reaim)) {
                if (reaim) controller.noteSeek(now / NANOS_PER_MS)
                logVerbose("[dd-sync] {} re-aiming landing {}ms off{}", shortId(), signed(filtered),
                    if (newTarget) " (new target)" else "")
                val quick = screen.seeksCheaply
                val aim = if (!newTarget && kind == (if (quick) LeadKind.QUICK_SEEK else LeadKind.SEEK))
                    learnerFor(kind)?.impliedLeadMs(landingLeadMs, filtered) else null
                seekPlaying(target, durationMs, now, aim)
                landingIsCorrection = true
                return
            }
        }

        // Just out of a game stall: packets queued behind it may still be catching the target up, so measure but
        // don't seek on it yet
        if (recentlyStalled(now, STALL_SETTLE_NANOS, LONG_STALL_MS)) {
            controller.observe(drift.toDouble(), now / NANOS_PER_MS)
            return
        }

        val decision = controller.update(drift.toDouble(), now / NANOS_PER_MS, cheapSeeks = screen.seeksCheaply)
        if (decision.seek) {
            seekPlaying(target, durationMs, now)
        } else {
            pushRatio(decision.ratio, now)
            if (decision.ratio != 1.0) note(Action.RATE)
        }
    }

    /** Server resumed while this player is paused: start it so it lands on the live position. */
    private fun resumeAligned() {
        val cold = coldAfterSnap || !screen.resumesWarm()
        coldAfterSnap = false
        warmAdopted = false
        if (cold && !screen.isLive && screen.canSeek() && estimator.hasTimeline && !estimator.paused) {
            // A cold start restarts the stream from the parked position and lands that much later; start
            // ahead by the learned restart time instead.
            val durationMs = screen.mediaPlayerDurationNanos / 1_000_000L
            val now = System.nanoTime()
            val target = estimator.targetMs(now / NANOS_PER_MS).roundToLong() + contentMs(coldLeadMs, estimator.rate)
            screen.seekVideoTo(clampSeek(target, loop, durationMs) * 1_000_000L)
            expectLanding(LeadKind.COLD)
        } else if (!screen.isLive && screen.canSeek()) {
            expectLanding(LeadKind.WARM)
        }
        correctAfterLanding = true
        controller.resetMeasurement()
        screen.applyServerPaused(false)
    }

    private fun seekPlaying(targetMs: Long, durationMs: Long, now: Long, aimLeadMs: Long? = null) {
        // An in-place seek keeps the last frame up and lands within a frame or two; a restart fades back in
        val quick = screen.seeksCheaply
        val kind = if (quick) LeadKind.QUICK_SEEK else LeadKind.SEEK
        // Overshoot by the learned restart cost so the post-restart position lands on the live point
        val lead = aimLeadMs ?: leadFor(kind)
        val seekMs = clampSeek(targetMs + contentMs(lead, estimator.rate), loop, durationMs)
        pushRatio(1.0, now)
        if (!quick) screen.clearRenderedFrameForTimeline()
        screen.seekVideoTo(seekMs * 1_000_000L)
        expectLanding(kind, lead)
        note(Action.SEEK)
    }

    private fun expectLanding(kind: LeadKind, leadMs: Long = leadFor(kind)) {
        landing = kind
        landingLeadMs = leadMs
        landingRunningSinceNanos = 0L
        landingIsCorrection = false
        landingStallMs = if (recentlyStalled(System.nanoTime(), STALL_LOOKBACK_NANOS)) lastStallMs else 0.0
        controller.resetMeasurement()
    }

    private fun learnLead(kind: LeadKind, landingDriftMs: Double, afterReaim: Boolean) {
        val learner = learnerFor(kind) ?: return
        val verdict = learner.onLanding(landingLeadMs, landingDriftMs, landingStallMs, isReaim = afterReaim)
        if (ServerOverrides.settings.logLevel >= com.dreamdisplays.core.display.policy.ClientLogLevel.VERBOSE) logger.info(
            "[dd-sync] {} landed {}ms after {} (aimed {}ms, stall {}ms): {}; leads seek={}ms quick={}ms cold={}ms",
            shortId(), signed(landingDriftMs), kind.name.lowercase(), landingLeadMs, landingStallMs.roundToLong(),
            verdict.name.lowercase(), seekLeadMs, quickSeekLeadMs, coldLeadMs,
        )
    }

    private fun learnerFor(kind: LeadKind): SeekLeadLearner? = when (kind) {
        LeadKind.SEEK -> seekLead
        LeadKind.QUICK_SEEK -> quickSeekLead
        LeadKind.COLD -> coldLead
        LeadKind.WARM -> null
    }

    private fun leadFor(kind: LeadKind): Long = learnerFor(kind)?.leadMs ?: 0L

    private fun pushRatio(ratio: Double, now: Long) {
        // Re-sent now and then as well, in case the player was swapped underneath
        if (ratio == appliedRatio && (ratio == 1.0 || now - lastRatioPushNanos < RATIO_REFRESH_NANOS)) return
        screen.setSyncPlaybackRate(ratio)
        appliedRatio = ratio
        lastRatioPushNanos = now
    }

    /** When the last correction that moves the picture (seek / snap) happened, for hitch attribution. */
    @Volatile
    var lastJumpNanos: Long = 0L
        private set

    @Volatile
    var lastJumpLabel: String = ""
        private set

    private fun note(action: Action) {
        if (action == Action.SEEK || action == Action.SNAP) {
            lastJumpNanos = System.nanoTime()
            lastJumpLabel = action.label
        }
        if (action.ordinal > intervalAction.ordinal) intervalAction = action
        if (action.ordinal > reportAction.ordinal) reportAction = action
        if (action == Action.SEEK || action == Action.SNAP) reportSeeks++
    }

    /** Sync figures for a viewer report: drift, rate and latency now, plus the strongest action and seeks since the last call. */
    internal class SyncReport(
        val active: Boolean, val driftMs: Double, val ratio: Double, val latencyCompMs: Double,
        val action: Int, val seeks: Int,
    )

    /** The current [SyncReport]; resets its per-report action and seek count. */
    fun takeReport(): SyncReport = synchronized(lock) {
        val active = screen.followsServerTimeline && estimator.hasTimeline && !screen.isLive && screen.videoStarted
        SyncReport(
            active = active,
            driftMs = if (active) controller.filteredMs ?: lastDriftMs else 0.0,
            ratio = appliedRatio,
            latencyCompMs = latencyCompMs,
            action = reportAction.ordinal,
            seeks = reportSeeks,
        ).also {
            reportAction = Action.NONE
            reportSeeks = 0
        }
    }

    private fun logIfDue(now: Long) {
        if (!estimator.hasTimeline || estimator.paused || screen.isLive || !screen.videoStarted) return
        // No measurement is taken while the player can't seek (errored, re-resolving); the last drift would be stale
        if (!screen.canSeek()) return
        // The server can change the interval or turn these lines off (0)
        val intervalNanos = ServerOverrides.settings.syncLogIntervalMs * 1_000_000L
        if (intervalNanos <= 0L || now - lastLogNanos < intervalNanos) return
        lastLogNanos = now
        val drift = controller.filteredMs ?: lastDriftMs
        logger.info(
            "[dd-sync] {} drift={}ms rate={} lat={}ms action={}",
            shortId(), signed(drift), "%.4f".format(java.util.Locale.ROOT, appliedRatio),
            latencyCompMs.roundToLong(), intervalAction.label,
        )
        intervalAction = Action.NONE
    }

    private fun logVerbose(format: String, vararg args: Any?) {
        if (ServerOverrides.settings.logLevel >= com.dreamdisplays.core.display.policy.ClientLogLevel.VERBOSE) {
            logger.info(format, *args)
        }
    }

    private fun shortId(): String = screen.uuid.toString().take(8)

    private fun signed(ms: Double): String = ms.roundToLong().let { if (it >= 0) "+$it" else "$it" }

    private fun projectTargetMs(packet: Pending): Long {
        if (packet.paused) return packet.targetMs
        val elapsedMs = (System.nanoTime() - packet.receivedAtNanos) / 1_000_000L
        return packet.targetMs + contentMs((elapsedMs + packet.latencyCompMs).roundToLong(), packet.rate)
    }

    /** Content ms that pass in [realMs] at playback [rate] (leads and latencies are real time). */
    private fun contentMs(realMs: Long, rate: Double): Long =
        if (rate == 1.0) realMs else (realMs * rate).roundToLong()

    private fun wrapForStart(ms: Long, loop: Boolean): Long =
        clampSeek(ms, loop, screen.mediaPlayerDurationNanos / 1_000_000L)

    /** Wraps a looping target into the media, or keeps a one-shot target inside it (never past the end). */
    private fun clampSeek(ms: Long, loop: Boolean, durationMs: Long): Long = when {
        loop && durationMs > 0 -> wrap(ms, true, durationMs)
        durationMs > 0 -> ms.coerceIn(0L, (durationMs - END_MARGIN_MS).coerceAtLeast(0L))
        else -> ms.coerceAtLeast(0L)
    }

    private fun wrap(ms: Long, loop: Boolean, durationMs: Long): Long =
        if (loop && durationMs > 0) ((ms % durationMs) + durationMs) % durationMs else ms.coerceAtLeast(0)

    private fun signedDrift(rawDiff: Long, loop: Boolean, durationMs: Long): Long {
        if (!loop || durationMs <= 0) return rawDiff
        val half = durationMs / 2
        return ((rawDiff % durationMs) + durationMs + half) % durationMs - half
    }

    private fun oneWayLatencyMs(): Double = Companion.oneWayLatencyMs()

    companion object {
        private val logger = LoggerFactory.getLogger(TimelineFollower::class.java)

        /** A scheduled start closer than this is played at once instead. */
        private const val MIN_SCHEDULE_MS = 2.0

        /** A karaoke lead change at least this big is jumped at once; smaller ones are closed by rate. */
        private const val LEAD_JUMP_MIN_MS = 60

        internal val START_SCHEDULER: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "DreamDisplays-ScheduledStart").apply { isDaemon = true }
            }

        /** Half the local player's round trip as the server last reported it, in ms. */
        fun oneWayLatencyMs(): Double = runCatching {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return@runCatching 0.0
            val rtt = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
            (rtt.coerceIn(0, MAX_RTT_MS) / 2.0)
        }.getOrDefault(0.0)

        private const val NANOS_PER_MS = 1_000_000.0
        private const val END_MARGIN_MS = 500L

        /** A player held at its end restarts once the shared clock is this far before the end (it wrapped / was seeked). */
        private const val END_HOLD_MS = 2_000L
        private const val SNAP_COOLDOWN_NANOS = 1_000_000_000L
        private const val PREPARED_SNAP_TOLERANCE_MS = 250
        private const val LANDING_SETTLE_NANOS = 1_500_000_000L

        /** A warm resume or an in-place seek settles fast: it measures after this long. */
        private const val QUICK_SETTLE_NANOS = 700_000_000L

        /** Off by more than this after a start or resume: correct it with one seek at once. */
        private const val POST_START_SEEK_MS = 100.0

        private const val MAX_QUICK_LEAD_MS = 1_500L
        private const val RATIO_REFRESH_NANOS = 1_000_000_000L
        private const val MAX_LEAD_MS = 3_000L

        /** A gap between client ticks longer than this is a game stall. */
        private const val STALL_NANOS = 250_000_000L

        /** A stall this recent before a seek still counts against its landing. */
        private const val STALL_LOOKBACK_NANOS = 1_000_000_000L

        /** A stall this long holds seek decisions for [STALL_SETTLE_NANOS] (shorter ones are just low frame rate). */
        private const val LONG_STALL_MS = 1_000.0

        /** After a long stall, no seek is decided for this long (queued packets may still move the target). */
        private const val STALL_SETTLE_NANOS = 1_000_000_000L
        private const val MAX_RTT_MS = 2_000

        /** Learned restart time of a seek while playing; shared by every display (it is this machine's cost). */
        private val seekLead = SeekLeadLearner(400L, MAX_LEAD_MS)

        /** Learned restart time of a stream start from a cold pause. */
        private val coldLead = SeekLeadLearner(600L, MAX_LEAD_MS)

        /** Learned landing offset of an in-place seek (decoder kept open; a prefill and the sound to line up). */
        private val quickSeekLead = SeekLeadLearner(150L, MAX_QUICK_LEAD_MS)

        private val seekLeadMs: Long get() = seekLead.leadMs
        private val coldLeadMs: Long get() = coldLead.leadMs
        private val quickSeekLeadMs: Long get() = quickSeekLead.leadMs
    }
}
