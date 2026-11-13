package com.dreamdisplays.media.player.preparation

import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.player.CacheInvalidator
import com.dreamdisplays.api.media.player.FrameUploaderFactory
import com.dreamdisplays.api.media.player.PlaybackConfig
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.player.RenderExecutor
import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.api.media.stream.StreamPreferences
import com.dreamdisplays.api.media.stream.StreamSelector
import com.dreamdisplays.api.media.stream.StreamSet
import com.dreamdisplays.media.player.readahead.ReadAhead
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaPreparationRoutingTest {
    private fun muxed(url: String) = MediaStream(
        url = url, type = MediaStreamType.VIDEO_AUDIO, codec = null, width = 1920, height = 1080, fps = 24.0,
        bitrate = null, audioTrackName = null, audioTrackLang = null,
    )

    private fun env(streamUrl: String) = object : PlaybackEnvironment {
        override val config: PlaybackConfig get() = error("unused")
        override val renderExecutor: RenderExecutor get() = error("unused")
        override val uploaderFactory: FrameUploaderFactory get() = error("unused")
        override val cacheInvalidator: CacheInvalidator get() = error("unused")
        override fun resolverChain(): MediaResolverRegistry = object : MediaResolverRegistry {
            override fun register(resolver: MediaResolverService) = Unit
            override fun unregister(resolver: MediaResolverService) = Unit
            override fun prefetch(source: MediaSource) = Unit
            override val resolvers: List<MediaResolverService> = emptyList()
            override fun resolve(source: MediaSource) = ResolvedMedia(
                streams = listOf(muxed(streamUrl)),
                metadata = MediaMetadata(null, null, 6_052_704L, null, null, null, null),
                isLive = false,
                isSeekable = true,
            )
        }

        override fun streamSelector(): StreamSelector = object : StreamSelector {
            override fun select(streams: List<MediaStream>, preferences: StreamPreferences) =
                StreamSet(streams.first(), streams.first(), streams)
        }
    }

    private fun prepared(pasted: String, streamUrl: String): PreparedMedia {
        ReadAhead.rememberDirectFile(
            streamUrl, ReadAhead.DirectFile(pasted, 3_821_521_682L, 6_052_704_000_000L, "video/mp4"),
        )
        return MediaPreparationService.prepare(
            pasted, "", VideoQuality.Auto, env(streamUrl), ReadAhead.Target(UUID.randomUUID(), null),
        )
    }

    @Test
    fun `a direct link that redirects to a signed CDN URL is read through the read-ahead`() {
        // Hugging Face: the pasted resolve link answers 302 to a signed CDN URL, which is what the resolver returns
        val pasted = "https://huggingface.co/datasets/x/y/resolve/main/movie.mp4"
        val signed = "https://us.aws.cdn.hf.co/xet-bridge-us/abc/def?X-Amz-Signature=${UUID.randomUUID()}"
        val streams = prepared(pasted, signed).streamSet
        assertTrue(streams.currentVideo.url.startsWith("http://127.0.0.1:"), streams.currentVideo.url)
        assertEquals(streams.currentVideo.url, streams.currentAudio.url)
        assertTrue(streams.availableVideo.all { it.url == streams.currentVideo.url })
        assertTrue(streams.availableAudio.all { it.url == streams.currentVideo.url })
    }

    @Test
    fun `a direct link served where it was pasted is read through the read-ahead`() {
        val url = "https://files.example/${UUID.randomUUID()}.mp4"
        val streams = prepared(url, url).streamSet
        assertTrue(streams.currentVideo.url.startsWith("http://127.0.0.1:"), streams.currentVideo.url)
        assertEquals(streams.currentVideo.url, streams.currentAudio.url)
    }

    @Test
    fun `route matches on the stream's own URL`() {
        val cdn = muxed("https://cdn.example/signed")
        val other = muxed("https://cdn.example/other")
        val loop = "http://127.0.0.1:1/token"
        assertEquals(loop, MediaPreparationService.route(cdn, cdn.url, loop, null).url)
        assertEquals(other.url, MediaPreparationService.route(other, cdn.url, loop, null).url)
        assertEquals(cdn.url, MediaPreparationService.route(cdn, cdn.url, null, null).url)
    }
}
