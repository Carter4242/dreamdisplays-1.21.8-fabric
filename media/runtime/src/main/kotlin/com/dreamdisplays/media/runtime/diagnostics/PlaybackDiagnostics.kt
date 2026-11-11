package com.dreamdisplays.media.runtime.diagnostics

import com.dreamdisplays.api.media.source.MediaSource
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** One resolver step for a link: which source ran and how it went. */
data class ResolveStep(
    val source: String,
    val client: String,
    val ok: Boolean,
    val error: String,
    val elapsedMs: Long,
    val atMs: Long,
)

/** A load / resolve / playback failure (or a visible hitch) of one display, as the player saw it. */
data class PlaybackFailure(
    val displayId: UUID,
    val url: String,
    /** `MediaFailureKind` name. */
    val kind: String,
    val message: String,
    /** Stage wire value: `resolve`, `open_stream`, `decode`, `audio`, `http_status`, `stall`, `seek`, `resume` ... */
    val stage: String,
    val httpStatus: Int = 0,
    val steps: List<ResolveStep> = emptyList(),
    /** Raw stderr (scrubbed and cut by the reporter). */
    val stderr: String = "",
    val retry: Int = 0,
    val maxRetries: Int = 0,
    val final: Boolean = false,
    val fatal: Boolean = false,
    val hitch: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val action: String = "",
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * What a visible freeze that is about to happen was caused by, noted by the player when it starts something that
 * stops the picture (a stall restart, a cold resume, a seek). The client's hitch tracker pairs it with the freeze it
 * then measures.
 */
data class HitchCause(
    val stage: String,
    val action: String,
    val message: String,
    val stderr: String = "",
    /** Frozen time before the cause was noticed (a stall is detected only after frames stopped for a while). */
    val frozenBeforeMs: Long = 0,
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * Hand-off point between the media modules (which see failures) and the client platform (which reports them to the
 * server). The platform installs [listener]; nothing is collected or sent without it.
 */
object PlaybackDiagnostics {
    /** Receives every failure and direct hitch. Called on media threads; must not block. */
    @Volatile
    var listener: ((PlaybackFailure) -> Unit)? = null

    /** The display audio line last opened (the `Display audio line` log message). */
    @Volatile
    var audioLine: String = ""

    private val causes = ConcurrentHashMap<UUID, HitchCause>()

    val enabled: Boolean get() = listener != null

    fun emit(failure: PlaybackFailure) {
        val l = listener ?: return
        runCatching { l(failure) }
    }

    /** Notes [cause] as the reason the picture of [displayId] is about to stop. */
    fun noteHitchCause(displayId: UUID, cause: HitchCause) {
        if (listener == null) return
        causes[displayId] = cause
    }

    /** Takes the cause noted for [displayId] at or after [sinceMs], if any. */
    fun takeHitchCause(displayId: UUID, sinceMs: Long): HitchCause? {
        val cause = causes.remove(displayId) ?: return null
        return cause.takeIf { it.atMs >= sinceMs }
    }

    fun forget(displayId: UUID) {
        causes.remove(displayId)
    }

    /** The display id in a player debug label (`<uuid>/<hash>`), or null. */
    fun displayIdOf(debugLabel: String): UUID? =
        runCatching { UUID.fromString(debugLabel.substringBefore('/')) }.getOrNull()
}

/**
 * Recent resolver steps per link, so a failure report can say which sources were tried and how each one failed.
 * Keyed by the link's resolvable form; bounded in links, steps per link and age.
 */
object ResolveTrail {
    private const val MAX_LINKS = 128
    private const val MAX_STEPS = 16
    private const val MAX_AGE_MS = 5 * 60_000L
    private const val MAX_STDERR = 8 * 1024

    private class Trail {
        val steps = ArrayDeque<ResolveStep>()
        var stderr: String = ""
        var stderrAtMs = 0L
    }

    private val trails = LinkedHashMap<String, Trail>(64, 0.75f, true)

    /** Records one step for [url]: [source] (`newpipe`, `yt-dlp binary` ...), its [client], outcome and error. */
    fun record(url: String, source: String, client: String? = null, ok: Boolean, error: String? = null, elapsedMs: Long = 0) {
        if (!PlaybackDiagnostics.enabled) return
        val step = ResolveStep(source, client.orEmpty(), ok, shortError(error), elapsedMs, System.currentTimeMillis())
        synchronized(this) {
            val trail = trailFor(url)
            trail.steps.addLast(step)
            while (trail.steps.size > MAX_STEPS) trail.steps.removeFirst()
        }
    }

    /** Remembers the stderr of the last failed external tool run for [url] (e.g. `yt-dlp`). */
    fun recordStderr(url: String, stderr: String) {
        if (!PlaybackDiagnostics.enabled || stderr.isBlank()) return
        synchronized(this) {
            val trail = trailFor(url)
            trail.stderr = stderr.takeLast(MAX_STDERR)
            trail.stderrAtMs = System.currentTimeMillis()
        }
    }

    /** Steps recorded for [url] in the last few minutes, oldest first. */
    fun steps(url: String): List<ResolveStep> = synchronized(this) {
        val now = System.currentTimeMillis()
        trails[keyOf(url)]?.steps?.filter { now - it.atMs <= MAX_AGE_MS }.orEmpty()
    }

    /** The last tool stderr recorded for [url] in the last few minutes, or empty. */
    fun stderr(url: String): String = synchronized(this) {
        val trail = trails[keyOf(url)] ?: return ""
        if (System.currentTimeMillis() - trail.stderrAtMs > MAX_AGE_MS) "" else trail.stderr
    }

    internal fun clear() = synchronized(this) { trails.clear() }

    private fun trailFor(url: String): Trail {
        val trail = trails.getOrPut(keyOf(url)) { Trail() }
        while (trails.size > MAX_LINKS) trails.remove(trails.keys.first())
        return trail
    }

    /** One key for every form of a link (`youtu.be/x`, `watch?v=x&t=3`). */
    fun keyOf(url: String): String = runCatching { MediaSource.from(url).toResolvableUrl() }.getOrNull() ?: url

    /** The most telling line of an error: the last non-blank one (tool output ends with the reason), cut short. */
    fun shortError(error: String?): String {
        if (error.isNullOrBlank()) return ""
        val lines = error.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val pick = lines.lastOrNull { it.contains("ERROR", ignoreCase = false) || it.contains("Error") } ?: lines.last()
        val prefix = lines.first().takeIf { it != pick && lines.size > 1 }?.take(80)?.let { "$it ... " }.orEmpty()
        return (prefix + pick).take(300)
    }
}
