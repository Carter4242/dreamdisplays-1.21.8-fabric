package com.dreamdisplays.platform.client.player.platform

import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.api.media.player.*
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.stream.StreamSelector
import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.runtime.get
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.source.direct.DirectStreamResolver
import com.dreamdisplays.media.source.platform.Platforms
import com.dreamdisplays.media.source.youtube.NewPipeResolver
import com.dreamdisplays.media.source.youtube.YtDlp
import com.dreamdisplays.media.source.youtube.YtStreamRefusals
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.render.DisplayYuvRenderTypes
import com.dreamdisplays.platform.client.render.GpuFrameUploader
import com.dreamdisplays.platform.client.render.ShaderPackCompat
import net.minecraft.client.Minecraft

/**
 * Minecraft-client implementation of [PlaybackEnvironment]: bridges the platform-agnostic media
 * player to the live client configuration, the render thread, the GPU uploader, the URL cache, and
 * the service registry. One shared instance is passed to every [MediaPlayer].
 */
object DreamPlaybackEnvironment : PlaybackEnvironment {
    override val config: PlaybackConfig = object : PlaybackConfig {
        override val defaultDisplayVolume: Double get() = ClientStateManager.config.defaultDisplayVolume

        override val useHwAccel: Boolean get() = ServerOverrides.useHwAccel

        override val isPremium: Boolean get() = ClientStateManager.isPremium

        override val gpuYuvActive: Boolean get() = DisplayYuvRenderTypes.active

        // Under a shader pack an alpha video plays opaque, on exactly the path every other video takes there: the
        // pack also draws displays inside its own level pass, where a translucent one has never been tested.
        override val transparentVideoAllowed: Boolean get() = !ShaderPackCompat.isShaderPackActive

        // The server's rescue switches for a decode path that misbehaves on some machines (`client.videoDecoder`)
        override val nativePipeAllowed: Boolean
            get() = ServerOverrides.settings.videoDecoder != DisplayPolicy.VideoDecoder.JVM

        override val inProcessDecodeAllowed: Boolean
            get() = ServerOverrides.settings.videoDecoder == DisplayPolicy.VideoDecoder.AUTO
    }

    override val renderExecutor: RenderExecutor =
        RenderExecutor { task -> Minecraft.getInstance().execute(task) }

    override val uploaderFactory: FrameUploaderFactory = FrameUploaderFactory { GpuFrameUploader() as FrameUploader }

    override val cacheInvalidator: CacheInvalidator = object : CacheInvalidator {
        override fun invalidate(url: String) {
            NewPipeResolver.invalidate(url)
            YtDlp.invalidateCache(url)
            Platforms.invalidate(url)
            DirectStreamResolver.invalidate(url)
        }

        override fun streamRefused(url: String) {
            // Counted before the purge, so the very next resolve already follows the escalated plan
            YtStreamRefusals.record(url)
            invalidate(url)
        }
    }

    override fun resolverChain(): MediaResolverRegistry = DreamServices.registry.get(MediaServices.RESOLVER_REGISTRY)

    override fun streamSelector(): StreamSelector = DreamServices.registry.get<StreamSelector>()
}
