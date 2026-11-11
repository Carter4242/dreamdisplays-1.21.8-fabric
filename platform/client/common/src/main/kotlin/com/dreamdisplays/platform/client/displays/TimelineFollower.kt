package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.sync.DriftController
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

    private var appliedRatio = 1.0
    private var lastRatioPushNanos = 0L
    private var lastDriftMs = 0.0
    private var intervalAction = Action.NONE
    private var lastLogNanos = 0L

    private enum class LeadKind { SEEK, COLD }

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
    )

    /** Clears follow state when a new video loads. */
    fun reset() {
        synchronized(lock) {
            lastServerTimeMs = Long.MIN_VALUE
            pending = null
            estimator.reset()
            controller.reset()
            landing = null
            landingRunningSinceNanos = 0L
            lastSnapNanos = 0L
            coldAfterSnap = false
            // The new player starts at rate 1; only our bookkeeping needs to follow
            appliedRatio = 1.0
            intervalAction = Action.NONE
        }
    }

    /** Re-applies a timeline packet that arrived before the media player existed or finished init. */
    fun onPlayerCreated() {
        synchronized(lock) { pending }?.let(::applyPending)
    }

    /**
     * Takes the server's [targetMs] / [paused] for the current media, wrapping by the locally known duration when
     * [loop] (Broadcast). Pause state is matched right away; position is corrected here and on every [tick].
     */
    fun apply(targetMs: Long, serverTimeMs: Long, paused: Boolean, loop: Boolean) {
        val packet = synchronized(lock) {
            if (serverTimeMs in 1..<lastServerTimeMs) return
            if (serverTimeMs > 0L) lastServerTimeMs = serverTimeMs
            val now = System.nanoTime()
            val comp = if (paused) 0.0 else oneWayLatencyMs()
            latencyCompMs = comp
            this.loop = loop
            if (estimator.onPacket(targetMs, paused, now / NANOS_PER_MS, comp)) controller.resetMeasurement()
            Pending(++nextSeq, targetMs, serverTimeMs, paused, loop, now, comp).also { pending = it }
        }
        applyPending(packet)
    }

    private fun applyPending(packet: Pending) {
        val startMs = projectTargetMs(packet) + if (packet.paused) 0L else coldLeadMs
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

    /** Called every client tick for a loaded display: measures drift and corrects it. */
    fun tick() {
        synchronized(lock) {
            if (!screen.followsServerTimeline) {
                pushRatio(1.0, System.nanoTime())
                return
            }
            evaluate(System.nanoTime())
            logIfDue(System.nanoTime())
        }
    }

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
        val drift = signedDrift(target - localMs, loop, durationMs)
        // drift > 0: this client is behind the server; < 0: ahead
        lastDriftMs = drift.toDouble()

        if (estimator.paused) {
            pushRatio(1.0, now)
            controller.resetMeasurement()
            landing = null
            if (!screen.isPaused) return // The pause itself is still being applied
            if (abs(drift) > settings.deadBandMs && now - lastSnapNanos >= SNAP_COOLDOWN_NANOS) {
                screen.seekVideoTo(target * 1_000_000L)
                lastSnapNanos = now
                coldAfterSnap = true
                note(Action.SNAP)
            }
            return
        }
        if (screen.isPaused) return // Resume is still being applied
        if (!screen.isClockRunning()) {
            // Restarting after a seek / resume: nothing to measure yet
            controller.resetMeasurement()
            landingRunningSinceNanos = 0L
            return
        }

        landing?.let { kind ->
            if (landingRunningSinceNanos == 0L) landingRunningSinceNanos = now
            val filtered = controller.observe(drift.toDouble(), now / NANOS_PER_MS)
            if (now - landingRunningSinceNanos < LANDING_SETTLE_NANOS) {
                pushRatio(1.0, now)
                return
            }
            learnLead(kind, filtered)
            landing = null
        }

        val decision = controller.update(drift.toDouble(), now / NANOS_PER_MS)
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
        if (cold && !screen.isLive && screen.canSeek() && estimator.hasTimeline && !estimator.paused) {
            // A cold start restarts the stream from the parked position and lands that much later; start
            // ahead by the learned restart time instead.
            val durationMs = screen.mediaPlayerDurationNanos / 1_000_000L
            val now = System.nanoTime()
            val target = estimator.targetMs(now / NANOS_PER_MS).roundToLong() + coldLeadMs
            screen.seekVideoTo(clampSeek(target, loop, durationMs) * 1_000_000L)
            expectLanding(LeadKind.COLD)
        }
        controller.resetMeasurement()
        screen.applyServerPaused(false)
    }

    private fun seekPlaying(targetMs: Long, durationMs: Long, now: Long) {
        // Overshoot by the learned restart cost so the post-restart position lands on the live point
        val seekMs = clampSeek(targetMs + seekLeadMs, loop, durationMs)
        pushRatio(1.0, now)
        screen.clearRenderedFrameForTimeline()
        screen.seekVideoTo(seekMs * 1_000_000L)
        expectLanding(LeadKind.SEEK)
        note(Action.SEEK)
    }

    private fun expectLanding(kind: LeadKind) {
        landing = kind
        landingRunningSinceNanos = 0L
        controller.resetMeasurement()
    }

    private fun learnLead(kind: LeadKind, landingDriftMs: Double) {
        // Landed behind (positive drift) means the restart took longer than the lead allowed for
        val step = (landingDriftMs * LEAD_GAIN).roundToLong()
        when (kind) {
            LeadKind.SEEK -> seekLeadMs = (seekLeadMs + step).coerceIn(0L, MAX_LEAD_MS)
            LeadKind.COLD -> coldLeadMs = (coldLeadMs + step).coerceIn(0L, MAX_LEAD_MS)
        }
        logger.info(
            "[dd-sync] {} landed {}ms after {}; leads seek={}ms cold={}ms",
            shortId(), signed(landingDriftMs), kind.name.lowercase(), seekLeadMs, coldLeadMs,
        )
    }

    private fun pushRatio(ratio: Double, now: Long) {
        // Re-sent now and then as well, in case the player was swapped underneath
        if (ratio == appliedRatio && (ratio == 1.0 || now - lastRatioPushNanos < RATIO_REFRESH_NANOS)) return
        screen.setSyncPlaybackRate(ratio)
        appliedRatio = ratio
        lastRatioPushNanos = now
    }

    private fun note(action: Action) {
        if (action.ordinal > intervalAction.ordinal) intervalAction = action
    }

    private fun logIfDue(now: Long) {
        if (!estimator.hasTimeline || estimator.paused || screen.isLive || !screen.videoStarted) return
        if (now - lastLogNanos < LOG_INTERVAL_NANOS) return
        lastLogNanos = now
        val drift = controller.filteredMs ?: lastDriftMs
        logger.info(
            "[dd-sync] {} drift={}ms rate={} lat={}ms action={}",
            shortId(), signed(drift), "%.4f".format(java.util.Locale.ROOT, appliedRatio),
            latencyCompMs.roundToLong(), intervalAction.label,
        )
        intervalAction = Action.NONE
    }

    private fun shortId(): String = screen.uuid.toString().take(8)

    private fun signed(ms: Double): String = ms.roundToLong().let { if (it >= 0) "+$it" else "$it" }

    private fun projectTargetMs(packet: Pending): Long {
        if (packet.paused) return packet.targetMs
        val elapsedMs = (System.nanoTime() - packet.receivedAtNanos) / 1_000_000L
        return packet.targetMs + elapsedMs + packet.latencyCompMs.roundToLong()
    }

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

    /** Half the local player's round trip as the server last reported it, in ms. */
    private fun oneWayLatencyMs(): Double = runCatching {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return@runCatching 0.0
        val rtt = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
        (rtt.coerceIn(0, MAX_RTT_MS) / 2.0)
    }.getOrDefault(0.0)

    companion object {
        private val logger = LoggerFactory.getLogger(TimelineFollower::class.java)

        private const val NANOS_PER_MS = 1_000_000.0
        private const val END_MARGIN_MS = 500L
        private const val SNAP_COOLDOWN_NANOS = 1_000_000_000L
        private const val LANDING_SETTLE_NANOS = 1_500_000_000L
        private const val RATIO_REFRESH_NANOS = 1_000_000_000L
        private const val LOG_INTERVAL_NANOS = 10_000_000_000L
        private const val MAX_LEAD_MS = 8_000L
        private const val MAX_RTT_MS = 2_000
        private const val LEAD_GAIN = 0.7

        /** Learned restart time of a seek while playing; shared by every display (it is this machine's cost). */
        @Volatile
        private var seekLeadMs = 1_000L

        /** Learned restart time of a stream start from a cold pause. */
        @Volatile
        private var coldLeadMs = 600L
    }
}
