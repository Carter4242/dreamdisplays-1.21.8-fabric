package com.dreamdisplays.media.player.preparation

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.StreamPreferences
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.StreamSelector
import com.dreamdisplays.media.player.policy.TransientResolveGap
import com.dreamdisplays.media.player.readahead.ReadAhead
import com.dreamdisplays.media.player.stream.ActiveStreams

/**
 * Resolves stream metadata via [MediaResolverRegistry], selects the best tracks via [StreamSelector],
 * and returns a [PreparedMedia] ready for playback. Runs on a background thread.
 */
internal object MediaPreparationService {

    /**
     * Resolves [url] through the registry's [MediaResolverRegistry], selects tracks via [StreamSelector], and returns a
     * [PreparedMedia] ready for playback. A direct media file is routed through [readAhead]'s buffer when one is given;
     * a live relay stream gets its SRT latency ([liveLatencyMs] unless the URL sets one, see [LiveStreamUrls.playUrl]).
     */
    fun prepare(
        url: String, lang: String, quality: VideoQuality, env: PlaybackEnvironment,
        readAhead: ReadAhead.Target? = null, liveLatencyMs: Int? = null,
    ): PreparedMedia {
        val chain: MediaResolverRegistry = env.resolverChain()
        val selector: StreamSelector = env.streamSelector()
        val source = MediaSource.from(url)
        val resolved = chain.resolve(source)

        // An extractor that came back empty-handed may do better on the next try; a direct link that
        // resolved to nothing will not, so only the platform case is marked as worth retrying.
        val platform = source !is MediaSource.DirectStream && source !is MediaSource.Remote
        fun gap(message: String) = if (platform) TransientResolveGap(message) else null

        if (resolved.streams.isEmpty()) {
            val message = "No streams available for $url."
            throw DreamMediaException.NotFound(message, gap(message))
        }

        val prefs = StreamPreferences(
            maxHeight = (quality.targetHeight ?: 1080).takeIf { it > 0 },
            preferFps60 = false,
            preferredAudioTrack = null,
            preferredAudioLanguage = lang.ifEmpty { null },
            allowHdr = false,
        )
        val selected = selector.select(resolved.streams, prefs)

        // An audio-only result is a distinct, actionable mistake (a music file / audio-only upload
        // pointed at a screen), not the generic "nothing usable" case, so it says so.
        val video = selected.videoStream ?: throw if (resolved.hasAudio && !platform) {
            DreamMediaException.NotFound("This link is audio only. A display needs a video to show.")
        } else {
            val message = if (resolved.hasAudio) "This link is audio only. A display needs a video to show."
            else "No usable video stream for $url."
            DreamMediaException.NotFound(message, gap(message))
        }
        val audio = selected.audioStream ?: run {
            val message = "No usable audio stream for $url."
            throw DreamMediaException.NotFound(message, gap(message))
        }

        val durationNanos = resolved.metadata.durationMs?.times(1_000_000L) ?: 0L

        // A progressive file read straight off its host has only the decoder's sub-second buffer
        // between a network stall and a frozen picture; routing it through the local read-ahead
        // buffer keeps tens of seconds downloaded ahead instead.
        val buffered = readAhead
            ?.takeIf { !resolved.isLive && resolved.isSeekable && video.url == audio.url && video.type.hasAudio }
            ?.let { ReadAhead.wrap(it, video.url) }
            ?.takeIf { it != video.url }
        fun MediaStream.routed(): MediaStream = when {
            buffered != null && url == video.url -> copy(url = buffered)
            LiveStreamUrls.isLive(url) -> copy(url = LiveStreamUrls.playUrl(url, liveLatencyMs))
            else -> this
        }

        return PreparedMedia(
            streamSet = ActiveStreams(
                availableVideo = resolved.videoStreams.map { it.routed() },
                availableAudio = resolved.audioStreams.map { it.routed() },
                currentVideo = video.routed(),
                currentAudio = audio.routed(),
            ),
            isLive = resolved.isLive,
            isSeekable = resolved.isSeekable,
            durationNanos = durationNanos,
            availableSubtitles = resolved.subtitles,
        )
    }
}
