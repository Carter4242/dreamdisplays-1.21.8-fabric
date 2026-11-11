package com.dreamdisplays.media.player.policy

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.MediaFailureKind
import com.dreamdisplays.api.media.source.MediaSource
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlin.random.Random

/**
 * Marks a resolve that came back without anything playable for a reason that may not hold on the
 * next try (an extractor that returned a partial format list, a platform API that answered empty).
 * Carried as the cause of the user-facing exception so the message stays the same.
 */
class TransientResolveGap(message: String) : RuntimeException(message)

/**
 * Decides whether a failed media load is worth trying again on its own: timeouts, dropped
 * connections, rate limits and server errors are; a link that is a web page, an audio file or a
 * dead (4xx) URL is not, and keeps failing fast with its own message.
 */
object LoadFailures {
    private val TRANSIENT_TEXT = [
        "timed out", "timeout", "connection reset", "connection refused", "connection closed",
        "broken pipe", "unexpected end of stream", "end of stream", "network is unreachable",
        "too many requests", "service unavailable", "bad gateway", "gateway timeout",
        "temporarily unavailable", "try again", "already in flight", "stream was reset",
    ]
    private val TRANSIENT_STATUS = Regex("""\b(HTTP|status|code)[ :=]*(408|425|429|5\d\d)\b""", RegexOption.IGNORE_CASE)

    /** True when [error] looks like a passing network or server condition rather than a wrong link. */
    fun isTransient(error: Throwable, source: MediaSource? = null): Boolean {
        val media = error as? DreamMediaException
        if (media != null) {
            if (causes(error).any { it is TransientResolveGap }) return true
            return when (media.kind) {
                MediaFailureKind.NETWORK, MediaFailureKind.TIMEOUT -> !media.isFatal
                MediaFailureKind.NOT_FOUND, MediaFailureKind.GEO_BLOCKED, MediaFailureKind.DECODE -> false
                // An extractor failure carries no verdict of its own: retry it when the platform
                // path is the only way to play the link, or when the cause says network.
                else -> !media.isFatal && (isPlatform(source) || causedByNetwork(error))
            }
        }
        if (causedByNetwork(error)) return true
        // Extractor chains surface plain exceptions ("all clients failed"); for a platform link that
        // is far more often a hiccup than a verdict, and a bounded retry costs little.
        return isPlatform(source) && error !is UnsupportedOperationException && error !is IllegalArgumentException
    }

    /** True when any cause in [error]'s chain is a network-level failure. */
    fun causedByNetwork(error: Throwable): Boolean = causes(error).any { e ->
        e is SocketTimeoutException || e is HttpTimeoutException || e is TimeoutException ||
            e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
            e is SocketException || e is SSLException || e is InterruptedIOException ||
            (e !is DreamMediaException && looksTransient(e.message))
    }

    /** True when [message] reads like a passing condition (timeout, reset, 429 / 5xx). */
    fun looksTransient(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return TRANSIENT_TEXT.any { it in text } || TRANSIENT_STATUS.containsMatchIn(message)
    }

    /** Whether [status] is an HTTP status that may well succeed when asked again. */
    fun isTransientStatus(status: Int): Boolean = status == 408 || status == 425 || status == 429 || status in 500..599

    /** Load attempts allowed for [source] before the failure is shown: fewer for extractor-backed platforms. */
    fun maxAttempts(source: MediaSource?): Int = if (isPlatform(source)) PLATFORM_ATTEMPTS else DIRECT_ATTEMPTS

    private fun isPlatform(source: MediaSource?): Boolean =
        source != null && source !is MediaSource.DirectStream && source !is MediaSource.Remote

    private fun causes(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(8)

    /** Direct links: the first try plus four retries, spread over roughly a minute. */
    const val DIRECT_ATTEMPTS = 5

    /** Extractor-backed platforms (YouTube, Twitch, ...): fewer retries so a dead video is not re-extracted at length. */
    const val PLATFORM_ATTEMPTS = 3
}

/**
 * Jittered back-off for automatic load retries. Every client of a server tends to load the same link
 * in the same second, so the delays are randomized (each one lands anywhere in ±[jitter] of its base)
 * to keep a crowd from retrying a struggling host in lockstep.
 */
class LoadRetrySchedule(
    private val baseDelaysMs: LongArray = DEFAULT_DELAYS_MS,
    private val jitter: Double = DEFAULT_JITTER,
    private val random: Random = Random.Default,
) {
    /** Retries scheduled since the last [reset]. */
    var retries: Int = 0
        private set

    /** Delay before retry number [retries] + 1, or null once [maxAttempts] loads (first try included) are used up. */
    fun next(maxAttempts: Int): Long? {
        if (retries + 1 >= maxAttempts) return null
        val base = baseDelaysMs[retries.coerceAtMost(baseDelaysMs.lastIndex)]
        retries++
        return jittered(base)
    }

    /** Clears the count after a load that worked. */
    fun reset() {
        retries = 0
    }

    internal fun jittered(base: Long): Long {
        if (jitter <= 0.0) return base
        val factor = 1.0 - jitter + random.nextDouble() * 2.0 * jitter
        return (base * factor).toLong().coerceAtLeast(MIN_DELAY_MS)
    }

    companion object {
        /** 2 s, 6 s, 15 s, 30 s: about 53 s in total before a direct link gives up. */
        val DEFAULT_DELAYS_MS = longArrayOf(2_000L, 6_000L, 15_000L, 30_000L)
        const val DEFAULT_JITTER = 0.35
        private const val MIN_DELAY_MS = 250L
    }
}
