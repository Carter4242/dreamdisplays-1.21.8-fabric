package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.core.services.PlaybackPort
import com.dreamdisplays.core.services.keys.PlaybackServices
import com.dreamdisplays.core.runtime.DreamDisplaysModule
import com.dreamdisplays.core.runtime.ModuleContext
import com.dreamdisplays.core.runtime.get
import com.dreamdisplays.core.runtime.register
import com.dreamdisplays.core.services.DefaultPlaybackService

/** Installs the playback service backed by the core display ports. */
object CorePlaybackModule : DreamDisplaysModule {
    override val id: String = "dreamdisplays:core_playback"

    override val dependencies: List<String> = [CoreDisplayModule.id]

    override fun install(context: ModuleContext) {
        val services = context.services
        val playbackService = DefaultPlaybackService(services.get<PlaybackPort>())

        services.register(PlaybackServices.PLAYBACK, playbackService)
    }
}
