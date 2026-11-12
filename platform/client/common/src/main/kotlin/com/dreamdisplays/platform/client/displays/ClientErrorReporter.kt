package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.diagnostics.ReportScrubber
import com.dreamdisplays.core.diagnostics.ReportThrottle
import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.ErrorStage
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.readahead.ReadAhead
import com.dreamdisplays.media.runtime.diagnostics.DisplaySignalsRegistry
import com.dreamdisplays.media.runtime.diagnostics.FrameGapDetector
import com.dreamdisplays.media.runtime.diagnostics.GcPauses
import com.dreamdisplays.media.runtime.diagnostics.HitchClassifier
import com.dreamdisplays.media.runtime.diagnostics.HitchSample
import com.dreamdisplays.media.runtime.diagnostics.PlaybackDiagnostics
import com.dreamdisplays.media.runtime.diagnostics.PlaybackFailure
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.capabilities.ClientEnvironmentProbe
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reports display failures and visible playback hitches to servers that ask for them
 * ([ServerFeature.CLIENT_ERROR_REPORTS]), plus a [ClientEnvironment] summary once per session (again only if it
 * changed, at most a few times). Nothing is collected or sent for other servers, and the player sees nothing.
 *
 * Failures arrive from media threads through [PlaybackDiagnostics]; hitches are measured here as the time a playing
 * display's clock stood still, paired with the cause the player noted. Everything is sent on the client thread.
 */
object ClientErrorReporter {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val ENV_FIRST_DELAY_MS = 15_000L
    private const val ENV_REFRESH_MS = 60_000L
    private const val MAX_ENV_SENDS = 4
    private const val MAX_QUEUED = 64
    private const val MIN_HITCH_MS = 300L
    private const val LONG_HITCH_MS = 60_000L
    private const val CAUSE_SLACK_MS = 3_000L
    private const val MAX_ATTEMPTS = 12

    private val queue = ConcurrentLinkedQueue<PlaybackFailure>()
    private val errors = ReportThrottle(dedupeWindowMs = 10 * 60_000L, perDisplayPerMinute = 4, totalPerMinute = 12)
    private val hitches = ReportThrottle(dedupeWindowMs = 60_000L, perDisplayPerMinute = 4, totalPerMinute = 12)
    private val shareReports = ConcurrentLinkedQueue<ClientErrorReport>()
    private val shares = ReportThrottle(dedupeWindowMs = 10 * 60_000L, perDisplayPerMinute = 4, totalPerMinute = 8)

    private class Episode(val startMs: Long, val positionMs: Long, var reported: Boolean = false)

    private val episodes = HashMap<UUID, Episode>()

    @Volatile
    private var enabled = false
    private var enabledAtMs = 0L

    // Environment summary state (client thread, except the build running on Client-IO)
    private var lastEnvSent: ClientEnvironment? = null
    private var envSends = 0
    private var envBuiltAtMs = 0L

    @Volatile
    private var envBuilding = false

    @Volatile
    private var envReady: ClientEnvironment? = null

    /** A server hello arrived: turn reporting on or off for this server. */
    fun onServerHello(hello: ServerHello) {
        val wanted = ServerFeature.CLIENT_ERROR_REPORTS.wire in hello.allowedFeatures
        if (wanted == enabled) return
        if (wanted) {
            enabled = true
            enabledAtMs = System.currentTimeMillis()
            PlaybackDiagnostics.listener = { failure -> if (queue.size < MAX_QUEUED) queue.add(failure) }
        } else {
            reset()
        }
    }

    /** Disconnect: stop collecting and forget the session. */
    fun reset() {
        enabled = false
        PlaybackDiagnostics.listener = null
        queue.clear()
        shareReports.clear()
        episodes.clear()
        errors.reset()
        hitches.reset()
        shares.reset()
        synchronized(histories) { histories.clear() }
        lastEnvSent = null
        envSends = 0
        envBuiltAtMs = 0L
        envReady = null
    }

    /** Hitches per display in the last minute (time, cause, gap ms), for viewer reports. Any thread. */
    private class HitchHistory {
        val times = LongArray(HISTORY)
        val gaps = IntArray(HISTORY)
        val causes = arrayOfNulls<String>(HISTORY)
        var next = 0
    }

    private const val HISTORY = 64
    private const val MINUTE_MS = 60_000L
    private val histories = HashMap<UUID, HitchHistory>()

    /** Summary of [displayId]'s hitches in the last minute: count, most common cause, worst gap in ms. */
    data class HitchSummary(val count: Int, val topCause: String, val worstGapMs: Int)

    fun hitchSummary(displayId: UUID, now: Long = System.currentTimeMillis()): HitchSummary = synchronized(histories) {
        val h = histories[displayId] ?: return HitchSummary(0, "", 0)
        var count = 0
        var worst = 0
        val byCause = HashMap<String, Int>()
        for (i in 0 until HISTORY) {
            val cause = h.causes[i] ?: continue
            if (now - h.times[i] > MINUTE_MS) continue
            count++
            if (h.gaps[i] > worst) worst = h.gaps[i]
            byCause.merge(cause, 1, Int::plus)
        }
        HitchSummary(count, byCause.maxByOrNull { it.value }?.key.orEmpty(), worst)
    }

    private fun remember(displayId: UUID, cause: String, gapMs: Long, now: Long) = synchronized(histories) {
        val h = histories.getOrPut(displayId) { HitchHistory() }
        h.times[h.next] = now
        h.gaps[h.next] = gapMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        h.causes[h.next] = cause
        h.next = (h.next + 1) % HISTORY
    }

    @Volatile
    private var ultraLogged = 0L
    private var ultraWindowStartMs = 0L

    /** At log level ultra, one compact line per hitch (bounded to a few hundred a minute). */
    private fun logHitch(line: String, now: Long) {
        if (PlaybackDiagnostics.logLevel < PlaybackDiagnostics.LOG_ULTRA) return
        synchronized(this) {
            if (now - ultraWindowStartMs > MINUTE_MS) {
                ultraWindowStartMs = now
                ultraLogged = 0
            }
            if (++ultraLogged > 300) return
        }
        logger.info(line)
    }

    /**
     * The frame-gap detector of [screen] found a stutter (render thread): attribute it, count it for the viewer
     * report, log it at ultra, and queue a report for servers that take them.
     */
    fun onStutter(screen: DisplayScreen, mp: MediaPlayer, gap: FrameGapDetector, nowNanos: Long) {
        GcPauses.install()
        val from = gap.gapStartNanos
        val signals = DisplaySignalsRegistry.of(screen.uuid)
        val (gcMs, gcDetail) = GcPauses.overlapping(from, nowNanos)
        val follower = runCatching { screen.timelineFollower }.getOrNull()
        val jumpAt = follower?.lastJumpNanos ?: 0L
        val noted = PlaybackDiagnostics.peekHitchCause(screen.uuid)
            ?.takeIf { it.stage == ErrorStage.SEEK.wire && System.currentTimeMillis() - it.atMs < 1_000L }
        val sync = when {
            jumpAt != 0L && jumpAt >= from - SYNC_SLACK_NANOS -> "${follower?.lastJumpLabel}"
            noted != null -> noted.action
            else -> null
        }
        fun within(at: Long) = at != 0L && at >= from - SIGNAL_SLACK_NANOS && at <= nowNanos
        val sample = HitchSample(
            gapMs = gap.gapNanos / 1_000_000L,
            expectedMs = gap.expectedNanos / 1_000_000L,
            worstGameFrameMs = gap.worstGameFrameNanos / 1_000_000L,
            gameFrameMs = gap.gameFrameNanos / 1_000_000L,
            gcPauseMs = gcMs,
            gcDetail = gcDetail,
            uploadMs = gap.worstUploadNanos / 1_000_000L,
            decodeStarved = within(signals.decodeStarvedAtNanos),
            lateDrops = within(signals.lateDropAtNanos),
            decodeQueue = signals.decodeQueue,
            audioUnderrun = within(signals.audioUnderrunAtNanos),
            audioBufferedMs = signals.audioBufferedMs,
            netBufferedBytes = runCatching { ReadAhead.bufferedBytes(screen.uuid) }.getOrDefault(-1L),
            syncAction = sync,
            pipeline = runCatching { mp.pipelineLabel() }.getOrDefault("?"),
        )
        val cause = HitchClassifier.classify(sample)
        val now = System.currentTimeMillis()
        remember(screen.uuid, cause, sample.gapMs, now)
        logHitch(HitchClassifier.logLine(screen.uuid.toString().take(8), cause, sample), now)
        if (!enabled || queue.size >= MAX_QUEUED) return
        queue.add(
            PlaybackFailure(
                displayId = screen.uuid,
                url = screen.videoUrl.orEmpty(),
                kind = "",
                message = "stutter: ${HitchClassifier.describe(cause, sample)}",
                stage = ErrorStage.STUTTER.wire,
                hitch = true,
                durationMs = sample.gapMs,
                positionMs = screen.currentTimeNanos / 1_000_000L,
                action = sync ?: "none",
                cause = cause,
                metrics = HitchClassifier.metrics(sample),
                atMs = now,
            )
        )
    }

    private const val SYNC_SLACK_NANOS = 200_000_000L
    private const val SIGNAL_SLACK_NANOS = 50_000_000L

    /** Client tick: measures hitches, keeps the environment summary current and sends what is due. */
    fun tick() {
        GcPauses.install()
        if (!enabled) {
            if (PlaybackDiagnostics.logLevel >= PlaybackDiagnostics.LOG_ULTRA) trackHitches(System.currentTimeMillis())
            return
        }
        val now = System.currentTimeMillis()
        trackHitches(now)
        val pending = queue.isNotEmpty()
        envReady?.let { env ->
            envReady = null
            if (env != lastEnvSent && envSends < MAX_ENV_SENDS) {
                runCatching { Initializer.sendPacket(env) }
                lastEnvSent = env
                envSends++
            }
        }
        val firstDue = lastEnvSent == null && now - enabledAtMs >= ENV_FIRST_DELAY_MS
        val refreshDue = pending && envSends < MAX_ENV_SENDS && now - envBuiltAtMs >= ENV_REFRESH_MS
        if (!envBuilding && (firstDue || refreshDue)) startEnvBuild(now)
        // Reports wait for a summary being built, so the server has it before the first one (bounded wait)
        if (envBuilding && now - envBuiltAtMs < 5_000L) return
        while (true) {
            val failure = queue.poll() ?: break
            send(failure, now)
        }
        while (true) {
            val report = shareReports.poll() ?: break
            val signature = ReportThrottle.signature(report.type, report.stage, report.action, report.message, 0, report.final)
            val suppressed = shares.admit(report.id, signature, now) ?: continue
            runCatching { Initializer.sendPacket(report.copy(suppressed = suppressed)) }
                .onFailure { logger.debug("Could not send a screen share report.", it) }
        }
    }

    /**
     * A screen share to [displayId] failed, or only worked after a fallback ([action]: what happened, e.g.
     * `fallback_ok`, `failed`). [attempts] are the pipeline tests (plan, adapter, ok, error); [metrics] the screens,
     * GPUs and the plan chosen. Scrubbed here; sent on the next client tick to servers that take reports. Any thread.
     */
    fun reportShare(
        displayId: UUID, message: String, stage: String, action: String, final: Boolean, stderr: String,
        attempts: List<ResolveAttemptReport>, metrics: Map<String, String>,
    ) {
        if (!enabled || shareReports.size >= MAX_QUEUED) return
        val homes = ClientEnvironmentProbe.homes()
        shareReports.add(
            ClientErrorReport(
                id = displayId,
                kind = "SHARE",
                message = ReportScrubber.scrub(message, homes).take(512),
                stage = stage.take(32),
                attempts = attempts.takeLast(MAX_ATTEMPTS).map {
                    it.copy(source = it.source.take(32), client = it.client.take(32),
                        error = ReportScrubber.scrub(it.error, homes).take(300))
                },
                stderr = ReportScrubber.excerpt(stderr, maxLines = 15, maxBytes = 2048, homes = homes),
                final = final,
                timestampMs = System.currentTimeMillis(),
                type = ClientErrorReport.TYPE_SHARE,
                action = action.take(32),
                metrics = metrics.entries.take(24).associate { (k, v) -> k.take(32) to ReportScrubber.scrub(v, homes).take(200) },
            )
        )
    }

    /**
     * A callback of this mod (screen, HUD, world render, tick, packet) threw and was contained at [site]. Queued as an
     * error report (stage `client`, kind `BUG`) with the top of the trace; the usual dedupe and rate limits apply,
     * so a failure that repeats every frame is sent once. Any thread.
     */
    fun reportContained(site: String, error: Throwable, displayId: UUID? = null) {
        if (!enabled || queue.size >= MAX_QUEUED) return
        queue.add(
            PlaybackFailure(
                displayId = displayId ?: NO_DISPLAY,
                url = "",
                kind = "BUG",
                message = "$site: $error",
                stage = STAGE_CONTAINED,
                stderr = com.dreamdisplays.platform.client.guard.FailureGuard.compactTrace(error),
                final = true,
            )
        )
    }

    private const val STAGE_CONTAINED = "client"
    private val NO_DISPLAY = UUID(0L, 0L)

    private fun startEnvBuild(now: Long) {
        envBuilding = true
        envBuiltAtMs = now
        val render = ClientEnvironmentProbe.renderFacts()
        DreamCoroutines.clientIo.launch {
            try {
                envReady = ClientEnvironmentProbe.build(render)
            } catch (e: Throwable) {
                logger.debug("Could not build the client environment summary.", e)
            } finally {
                envBuilding = false
            }
        }
    }

    private fun send(f: PlaybackFailure, now: Long) {
        val type = if (f.hitch) ClientErrorReport.TYPE_HITCH else ClientErrorReport.TYPE_ERROR
        val throttle = if (f.hitch) hitches else errors
        // Stutters are deduplicated by cause (their figures differ every time); other reports by their text
        val signature = if (f.cause.isNotEmpty()) ReportThrottle.signature(type, f.stage, f.cause, "", 0, false)
        else ReportThrottle.signature(type, f.stage, if (f.hitch) f.action else f.kind, f.message, f.retry, f.final)
        val suppressed = throttle.admit(f.displayId, signature, now) ?: return
        runCatching { Initializer.sendPacket(toPacket(f, suppressed, now)) }
            .onFailure { logger.debug("Could not send an error report.", it) }
    }

    /** The wire report for [f]: scrubbed, cut to size. */
    internal fun toPacket(f: PlaybackFailure, suppressed: Int, now: Long, homes: List<String> = ClientEnvironmentProbe.homes()): ClientErrorReport {
        val url = f.url.ifEmpty { DisplayRegistry.screens[f.displayId]?.videoUrl.orEmpty() }
        return ClientErrorReport(
            id = f.displayId,
            url = ReportScrubber.scrubUrl(url).take(512),
            kind = f.kind.take(32),
            message = ReportScrubber.scrub(f.message, homes).take(512),
            stage = f.stage.take(32),
            httpStatus = f.httpStatus,
            attempts = f.steps.takeLast(MAX_ATTEMPTS).map { step ->
                ResolveAttemptReport(
                    source = step.source.take(32),
                    client = step.client.take(32),
                    ok = step.ok,
                    error = ReportScrubber.scrub(step.error, homes).take(300),
                    elapsedMs = step.elapsedMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    agoMs = (now - step.atMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                )
            },
            stderr = ReportScrubber.excerpt(f.stderr, maxLines = 15, maxBytes = 2048, homes = homes),
            retry = f.retry,
            maxRetries = f.maxRetries,
            final = f.final,
            fatal = f.fatal,
            timestampMs = f.atMs,
            suppressed = suppressed,
            type = if (f.hitch) ClientErrorReport.TYPE_HITCH else ClientErrorReport.TYPE_ERROR,
            durationMs = f.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            positionMs = f.positionMs,
            action = f.action.take(32),
            cause = f.cause.take(32),
            metrics = f.metrics.entries.take(24).associate { (k, v) -> k.take(32) to ReportScrubber.scrub(v, homes).take(200) },
        )
    }

    /**
     * A playing display whose clock stands still is frozen for the viewer. Each freeze of [MIN_HITCH_MS] or more is
     * reported once it ends, with the cause the player noted when it started (a stall restart, a cold resume, a
     * seek); one without a noted cause is a buffering underrun. A freeze that ends in an error is left to the error.
     */
    private fun trackHitches(now: Long) {
        val seen = HashSet<UUID>()
        for (screen in DisplayRegistry.getScreens()) {
            if (screen.isDormant || screen.videoUrl.isNullOrEmpty()) continue
            val id = screen.uuid
            seen += id
            val frozen = !screen.errored && screen.videoStarted && !screen.isPaused && !screen.isClockRunning()
            val episode = episodes[id]
            if (frozen) {
                if (episode == null) {
                    episodes[id] = Episode(now, screen.currentTimeNanos / 1_000_000L)
                } else if (!episode.reported && now - episode.startMs >= LONG_HITCH_MS) {
                    // Still frozen after a minute: say so now rather than only when (if) it recovers
                    episode.reported = true
                    queueHitch(screen, episode, now, stillFrozen = true)
                }
                continue
            }
            if (episode == null) continue
            episodes.remove(id)
            if (screen.errored || episode.reported) {
                PlaybackDiagnostics.forget(id)
                continue
            }
            queueHitch(screen, episode, now, stillFrozen = false)
        }
        episodes.keys.retainAll(seen)
    }

    private fun queueHitch(screen: DisplayScreen, episode: Episode, now: Long, stillFrozen: Boolean) {
        val cause = PlaybackDiagnostics.takeHitchCause(screen.uuid, episode.startMs - CAUSE_SLACK_MS)
        val duration = now - episode.startMs + (cause?.frozenBeforeMs ?: 0L)
        if (duration < MIN_HITCH_MS) return
        val stage = cause?.stage ?: ErrorStage.UNDERRUN.wire
        val action = (cause?.action ?: "wait") + if (stillFrozen) " (still frozen)" else ""
        remember(screen.uuid, stage, duration, now)
        logHitch(
            "[dd-hitch] ${screen.uuid.toString().take(8)} freeze=${duration}ms cause=$stage action=$action" +
                (cause?.message?.let { " ($it)" } ?: ""),
            now,
        )
        if (!enabled || queue.size >= MAX_QUEUED) return
        queue.add(
            PlaybackFailure(
                displayId = screen.uuid,
                url = screen.videoUrl.orEmpty(),
                kind = if (cause == null) "NETWORK" else "",
                message = cause?.message ?: "playback waited for media",
                stage = stage,
                httpStatus = com.dreamdisplays.media.player.policy.FailureStages.httpStatus(cause?.stderr, cause?.message),
                stderr = cause?.stderr.orEmpty(),
                hitch = true,
                durationMs = duration,
                positionMs = episode.positionMs,
                action = action,
                atMs = episode.startMs,
            )
        )
    }
}
