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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    /** [resolve] calls in progress per URL: they share one fetch, which [cancel] stops only for a lone caller. */
    private val resolving = ConcurrentHashMap<String, AtomicInteger>()

    override fun cancel(source: MediaSource) {
        val url = source.toResolvableUrl() ?: return
        if (resolving[url]?.get() == 1) YtDlp.cancelFetch(url)
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        val url = source.toResolvableUrl()
            ?: throw UnsupportedOperationException("$source has no resolvable URL.")
        // The format cache wraps every failure in an IOException; unwrap a typed one so its message and fatality reach
        // the player instead of a generic "fetch failed"
        resolving.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
        val streams = try {
            YtDlp.fetch(url)
        } catch (e: IOException) {
            throw (e.cause as? DreamMediaException) ?: e
        } finally {
            resolving.computeIfPresent(url) { _, n -> if (n.decrementAndGet() <= 0) null else n }
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
