package com.dreamdisplays.media.player.readahead

import com.dreamdisplays.media.runtime.security.MediaHostGuard
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.UUID

/**
 * Read-ahead for direct media files (MP4 / WebM / Matroska / QuickTime served straight off a host).
 *
 * The native demuxer reads such a file on demand and keeps well under a second decoded, so any
 * network stall longer than the OS socket buffer freezes the picture. Here the decoders read the
 * file from a loopback endpoint ([ReadAheadServer]) instead, which keeps the next
 * [DEFAULT_SECONDS] (or the display policy's `network.read_ahead_seconds`) downloaded ahead of
 * playback, serves seeks from the same cache, and refreshes an expired signed link by re-resolving
 * the original one. HLS, YouTube, Twitch and every other extractor source are left alone.
 */
object ReadAhead {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Seconds downloaded ahead of playback when the server's policy leaves it unset. */
    const val DEFAULT_SECONDS = 30

    /** Upper bound on a policy-supplied value; the byte cap per display applies regardless. */
    const val MAX_SECONDS = 120

    private const val MIN_WINDOW_BYTES = 4L shl 20
    private const val MAX_WINDOW_BYTES = 48L shl 20

    /** Bitrate assumed for a file whose duration is unknown (~8 Mbit/s). */
    private const val FALLBACK_BYTES_PER_SECOND = 1L shl 20
    private const val MAX_REMEMBERED_FILES = 512

    private val defaultSeconds: Int =
        System.getProperty("dreamdisplays.readAheadSeconds")?.toIntOrNull()?.coerceIn(0, MAX_SECONDS)
            ?: DEFAULT_SECONDS

    private val BUFFERED_TYPES = setOf(
        "video/mp4", "video/x-m4v", "video/quicktime", "video/webm", "video/x-matroska",
    )

    /** Who is asking: the display (one buffer per display and file) and its policy's read-ahead, if any. */
    data class Target(val displayId: UUID, val seconds: Int?)

    /** A progressive file the direct resolver vouched for: what it was pasted as, and how big and long it is. */
    data class DirectFile(
        val originalUrl: String,
        val contentLength: Long,
        val durationNanos: Long?,
        val contentType: String?,
    )

    private val directFiles = object : LinkedHashMap<String, DirectFile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DirectFile>): Boolean =
            size > MAX_REMEMBERED_FILES
    }

    private val serverHolder = lazy {
        ReadAheadServer(refresher = ::refresh).also { s ->
            MediaHostGuard.trustLocalUrls(s::owns)
        }
    }
    private val server: ReadAheadServer by serverHolder

    /**
     * Records that [streamUrl] is a seekable progressive file. Only files recorded here are ever
     * buffered; anything else (manifests, platform streams) passes through [wrap] untouched.
     */
    fun rememberDirectFile(streamUrl: String, file: DirectFile) {
        if (file.contentLength <= 0L) return
        val type = file.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (type !in BUFFERED_TYPES) return
        synchronized(directFiles) { directFiles[streamUrl] = file }
    }

    /**
     * The URL the decoders should open for [streamUrl]: a loopback read-ahead URL when it is a
     * remembered direct file and read-ahead is on for [target], else [streamUrl] itself.
     */
    fun wrap(target: Target, streamUrl: String): String {
        val file = synchronized(directFiles) { directFiles[streamUrl] } ?: return streamUrl
        val seconds = (target.seconds ?: defaultSeconds).coerceIn(0, MAX_SECONDS)
        if (seconds == 0) return streamUrl
        val window = ReadAheadMath.windowBytes(
            seconds, file.contentLength, file.durationNanos,
            MIN_WINDOW_BYTES, MAX_WINDOW_BYTES, FALLBACK_BYTES_PER_SECOND,
        )
        return runCatching { server.register(target.displayId, file, streamUrl, window) }
            .onFailure { logger.warn("Read-ahead unavailable for this file: ${it.message}.") }
            .getOrNull()
            ?.also { logger.debug("Read-ahead on for {} ({} KiB window).", file.originalUrl.take(120), window / 1024) }
            ?: streamUrl
    }

    /** Called when [displayId]'s player stops: its buffers are dropped once no decoder still reads them. */
    fun release(displayId: UUID) {
        if (serverHolder.isInitialized()) server.release(displayId)
    }

    /** A fresh URL for [originalUrl]: walks its redirects again, so an expired signed target is replaced. */
    private fun refresh(originalUrl: String): String {
        MediaHostGuard.invalidate(originalUrl)
        return MediaHostGuard.resolveSafeUrl(originalUrl)
    }
}
