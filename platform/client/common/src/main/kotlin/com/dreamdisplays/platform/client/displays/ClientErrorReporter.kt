package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.diagnostics.ReportScrubber
import com.dreamdisplays.core.diagnostics.ReportThrottle
import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.ErrorStage
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import com.dreamdisplays.core.protocol.common.packets.ServerHello
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
        episodes.clear()
        errors.reset()
        hitches.reset()
        lastEnvSent = null
        envSends = 0
        envBuiltAtMs = 0L
        envReady = null
    }

    /** Client tick: measures hitches, keeps the environment summary current and sends what is due. */
    fun tick() {
        if (!enabled) return
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
    }

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
        val signature = ReportThrottle.signature(
            type, f.stage, if (f.hitch) f.action else f.kind, f.message, f.retry, f.final,
        )
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
        if (queue.size >= MAX_QUEUED) return
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
