package com.dreamdisplays.core.diagnostics

import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import java.util.*

/**
 * Server-side caps for client-supplied diagnostics: every string is cut to a fixed size and stripped of control
 * characters (stderr keeps its line breaks), lists are bounded, and private details are scrubbed again in case an
 * older or modified client skipped it. [Rate] bounds how many packets one player may have logged per minute.
 */
object ReportLimits {
    const val MAX_SHORT = 64
    const val MAX_TEXT = 512
    const val MAX_URL = 512
    const val MAX_ATTEMPT_ERROR = 300
    const val MAX_ATTEMPTS = 16
    const val MAX_STDERR_BYTES = 2048
    const val MAX_STDERR_LINES = 15
    const val MAX_ENV_FIELD = 256
    const val MAX_ENV_LIST = 32
    const val MAX_METRICS = 24

    fun sanitize(r: ClientErrorReport): ClientErrorReport = r.copy(
        url = text(ReportScrubber.scrubUrl(r.url), MAX_URL),
        kind = short(r.kind),
        message = text(ReportScrubber.scrub(r.message), MAX_TEXT),
        stage = short(r.stage),
        httpStatus = r.httpStatus.takeIf { it in 100..999 } ?: 0,
        attempts = r.attempts.take(MAX_ATTEMPTS).map(::sanitize),
        stderr = ReportScrubber.excerpt(stripControl(r.stderr, keepNewlines = true), MAX_STDERR_LINES, MAX_STDERR_BYTES),
        retry = r.retry.coerceIn(0, 999),
        maxRetries = r.maxRetries.coerceIn(0, 999),
        suppressed = r.suppressed.coerceIn(0, 1_000_000),
        type = if (r.type == ClientErrorReport.TYPE_HITCH) ClientErrorReport.TYPE_HITCH else ClientErrorReport.TYPE_ERROR,
        durationMs = r.durationMs.coerceIn(0, 86_400_000),
        positionMs = r.positionMs.coerceIn(0L, 1_000L * 86_400_000L),
        action = short(r.action),
        cause = short(r.cause),
        metrics = r.metrics.entries.take(MAX_METRICS)
            .associate { (k, v) -> short(k) to text(ReportScrubber.scrub(v), MAX_ENV_FIELD) },
    )

    private fun sanitize(a: ResolveAttemptReport): ResolveAttemptReport = a.copy(
        source = short(a.source),
        client = short(a.client),
        error = text(ReportScrubber.scrub(a.error), MAX_ATTEMPT_ERROR),
        elapsedMs = a.elapsedMs.coerceIn(0, 3_600_000),
        agoMs = a.agoMs.coerceIn(0, 86_400_000),
    )

    fun sanitize(e: ClientEnvironment): ClientEnvironment {
        fun f(s: String) = text(ReportScrubber.scrub(s), MAX_ENV_FIELD)
        fun l(list: List<String>) = list.take(MAX_ENV_LIST).map { short(it) }.filter { it.isNotEmpty() }
        return ClientEnvironment(
            osName = f(e.osName), osVersion = f(e.osVersion), osArch = f(e.osArch),
            javaVersion = f(e.javaVersion), javaVendor = f(e.javaVendor),
            gpuVendor = f(e.gpuVendor), gpuRenderer = f(e.gpuRenderer), glVersion = f(e.glVersion),
            ytDlpMode = f(e.ytDlpMode), ytDlpVersion = f(e.ytDlpVersion), ytDlpPython = f(e.ytDlpPython),
            ytDlpPythonVersion = f(e.ytDlpPythonVersion), ytDlpNote = f(e.ytDlpNote),
            ffmpegSource = f(e.ffmpegSource), ffmpegVersion = f(e.ffmpegVersion),
            ffmpegDecoders = l(e.ffmpegDecoders), ffmpegFilters = l(e.ffmpegFilters),
            hwAccel = f(e.hwAccel), nativeLoaded = e.nativeLoaded, nativeUnavailableReason = f(e.nativeUnavailableReason),
            lavLoaded = e.lavLoaded, lavUnavailableReason = f(e.lavUnavailableReason), audioLine = f(e.audioLine),
            shaderPack = f(e.shaderPack), ytDlpPath = f(e.ytDlpPath), ffmpegPath = f(e.ffmpegPath),
        )
    }

    /** Text safe to put in a chat component: control characters and the legacy `§` color marker removed. */
    fun forChat(text: String, max: Int = MAX_TEXT): String = text(text, max).replace('§', '?')

    private fun short(s: String): String = text(s, MAX_SHORT)

    private fun text(s: String, max: Int): String = stripControl(s, keepNewlines = false).take(max)

    private fun stripControl(s: String, keepNewlines: Boolean): String =
        s.filter { c -> !c.isISOControl() || (keepNewlines && c == '\n') || c == '\t' }.replace('\t', ' ')

    /** Sliding one-minute window per player: at most [perMinute] accepted packets. Thread-safe. */
    class Rate(private val perMinute: Int) {
        private val windows = HashMap<UUID, ArrayDeque<Long>>()

        @Synchronized
        fun tryAcquire(player: UUID, nowMs: Long): Boolean {
            val times = windows.getOrPut(player) { ArrayDeque() }
            while (times.isNotEmpty() && nowMs - times.first() >= 60_000L) times.removeFirst()
            if (times.size >= perMinute) return false
            times.addLast(nowMs)
            return true
        }

        @Synchronized
        fun forget(player: UUID) {
            windows.remove(player)
        }
    }
}
