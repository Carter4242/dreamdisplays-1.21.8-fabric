package com.dreamdisplays.media.player.policy

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.player.util.MediaUtil

/**
 * Encapsulates retry logic for stream failures: decides whether to retry, whether to invalidate
 * the `yt-dlp` URL cache, and provides the exponential back-off delay for the next attempt.
 */
internal class RetryPolicy(private val maxRetries: Int = 3) {
    private val backoffMs = longArrayOf(1000, 3000, 8000)

    /** Number of retry attempts made so far. */
    var retries = 0
        private set

    /** True when all retries are exhausted. */
    val exhausted: Boolean get() = retries >= maxRetries

    /**
     * Analyses [stderr] and [normalEos] to decide whether and how to retry.
     * Returns `null` when retries are exhausted or the error is unrecoverable.
     *
     * @param isLive whether the current stream is a live stream
     */
    fun evaluate(stderr: String, normalEos: Boolean, isLive: Boolean): Decision? {
        if (exhausted) return null
        val refused = isRefused(stderr)
        val gone = isNotFound(stderr)
        if (refused || gone || MediaUtil.isTransientError(stderr)) {
            return Decision(invalidateCache = refused || gone, refused = refused)
        }
        if (normalEos && isLive) return Decision(invalidateCache = true)
        return null
    }

    /**
     * Returns the back-off delay in milliseconds for the next attempt and increments [retries].
     * Call this immediately before scheduling the retry.
     */
    fun nextDelay(): Long = backoffMs[retries.coerceAtMost(backoffMs.lastIndex)].also { retries++ }

    /** Resets [retries] to 0 after a successful stream start. */
    fun reset() {
        retries = 0
    }

    /** Describes how a retry should be performed. */
    data class Decision(
        /** If `true`, the `yt-dlp` cache must be purged before re-fetching stream URLs. */
        val invalidateCache: Boolean,
        /**
         * If `true`, the host refused the stream URLs (HTTP 403): the resolvers are told, so a repeated refusal
         * is resolved from a different source rather than the same kind of URL again.
         */
        val refused: Boolean = false,
    )

    companion object {
        private val REFUSED = Regex("""\b403\s+Forbidden\b|(returned|error|status)[ :=]*403\b|\bForbidden\b""")
        private val NOT_FOUND = Regex("""\b404\s+Not Found\b|(returned|error|status)[ :=]*404\b|\bNot Found\b""")

        /** True when `FFmpeg`'s [stderr] says the host refused the stream (HTTP 403). */
        fun isRefused(stderr: String): Boolean = REFUSED.containsMatchIn(stderr)

        /** True when `FFmpeg`'s [stderr] says the stream URL no longer exists (HTTP 404). */
        fun isNotFound(stderr: String): Boolean = NOT_FOUND.containsMatchIn(stderr)

        /**
         * The error shown once retries are used up. A refused stream (403) or a lost connection is a passing,
         * non-fatal [DreamMediaException.Network] the display may retry on its own later; anything else is a verdict
         * on the stream itself.
         */
        fun exhaustedError(stderr: String, normalEos: Boolean, source: MediaSource?): DreamMediaException = when {
            !normalEos && isRefused(stderr) -> DreamMediaException.Network(
                if (source is MediaSource.YouTube) "YouTube refused the video stream (403). Try again in a minute."
                else "The video host refused the stream (403). Try again in a minute."
            )

            !normalEos && LoadFailures.looksTransient(stderr) -> DreamMediaException.Network("Lost the connection to the video.")
            else -> DreamMediaException.Decode("Unrecoverable stream failure", isFatal = true)
        }
    }
}
