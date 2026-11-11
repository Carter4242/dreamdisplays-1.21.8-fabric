package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.core.runtime.DreamDisplaysModule
import com.dreamdisplays.core.runtime.ModuleContext
import com.dreamdisplays.media.audio.engine.AcousticsEngine
import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.platform.client.managers.ClientStateManager
import java.util.UUID

/** Installs the 3D acoustics engine and seeds it with the current config (quality tier, output profile, loudness normalization). */
object ClientAudioModule : DreamDisplaysModule {
    private var engine: AcousticsEngine? = null

    /** Applies a changed loudness normalization setting to every display at once. */
    fun setLoudnessNormalization(enabled: Boolean) {
        engine?.setLoudnessNormalization(enabled)
    }

    /** Re-applies the output profile and normalization the player and server settle on (see [ServerOverrides]). */
    fun applyOutputSettings() {
        val e = engine ?: return
        e.setBinauralOutput(ServerOverrides.audioBinauralOutput)
        e.setLoudnessNormalization(ServerOverrides.audioNormalization)
    }

    /** Gives one display its own acoustics tier, or null to follow the global one again. */
    fun setSourceQuality(id: UUID, quality: AcousticQuality?) {
        engine?.setSourceQuality(id, quality)
    }

    override val id: String = "dreamdisplays:client_audio"

    override fun install(context: ModuleContext) {
        val engine = AcousticsEngine().also { this.engine = it }
        engine.setGlobalQuality(ClientStateManager.config.audioAcoustics)
        engine.setBinauralOutput(ServerOverrides.audioBinauralOutput)
        engine.setLoudnessNormalization(ServerOverrides.audioNormalization)
        context.services.register(AudioAcousticsServices.ACOUSTICS, engine)
    }
}
