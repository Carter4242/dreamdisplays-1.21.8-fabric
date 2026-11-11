package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.CustomMediaKind
import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.media.source.RaceAware
import java.io.IOException

/**
 * Subprocess-backed [MediaResolverService] wrapping the [YtDlp] orchestrator. This is the slow, robust
 * fallback behind [NewPipeResolver]: a lower [priority] means [MediaResolverRegistry]
 * only reaches it after the in-process fast path has declined or failed.
 */
object YtDlpResolver : MediaResolverService, RaceAware {
    override val priority: Int = 0

    /** Everything except a live relay stream, which only `FFmpeg` itself can open (see [CustomMediaKind.LIVE]). */
    override fun canResolve(source: MediaSource): Boolean =
        !(source is MediaSource.DirectStream && source.kind == CustomMediaKind.LIVE)

    override fun prefetch(source: MediaSource): Boolean {
        val url = source.toResolvableUrl() ?: return false
        YtDlp.prefetchFormats(url)
        return true
    }

    /** The fetch [resolve] waits on for [source] stops only when nothing else waits on it ([YtDlp.cancelFetch]). */
    override fun cancel(source: MediaSource) {
        val url = source.toResolvableUrl() ?: return
        YtDlp.cancelFetch(url, owner = source)
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        val url = source.toResolvableUrl()
            ?: throw UnsupportedOperationException("$source has no resolvable URL.")
        // The format cache wraps every failure in an IOException; unwrap a typed one so its message and fatality reach
        // the player instead of a generic "fetch failed"
        val streams = try {
            // The source is this resolve's identity: the race that runs it cancels with the same one
            YtDlp.fetch(url, owner = source)
        } catch (e: IOException) {
            throw (e.cause as? DreamMediaException) ?: e
        }
        check(streams.isNotEmpty()) { "yt-dlp returned no playable streams for $url." }

        val isLive = streams.any { it.isLive }
        val durationNanos = streams.firstOrNull { it.durationNanos > 0L }?.durationNanos ?: 0L
        val first = streams.first()
        val metadata = MediaMetadata.UNKNOWN.copy(
            title = first.title,
            uploader = first.uploaderName,
            thumbnailUrl = first.thumbnailUrl,
            viewCount = first.viewCount,
            durationMs = durationNanos.takeIf { it > 0L }?.div(1_000_000L),
        )
        return ResolvedMedia(
            streams = streams.map { it.toMediaStream() },
            metadata = metadata,
            isLive = isLive,
            isSeekable = streams.any { it.isSeekable },
        )
    }
}
