package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourceAcousticState
import com.dreamdisplays.api.media.audio.AudioAcousticsService
import com.dreamdisplays.api.media.audio.AudioDspStage
import kotlinx.atomicfu.atomic
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [AudioAcousticsService]: owns one [AudioRenderChain] per registered display and the shared
 * listener pose / global quality tier / output profile / loudness normalization they all read from.
 */
class AcousticsEngine(private val sampleRate: Float = 44100f) : AudioAcousticsService {
    private val chains = ConcurrentHashMap<UUID, AudioRenderChain>()
    private val listenerRef = atomic(ListenerPose.IDENTITY)
    private val qualityRef = atomic(AcousticQuality.ADVANCED)
    private val binauralRef = atomic(true)
    private val normalizationRef = atomic(true)
    private val sourceQuality = ConcurrentHashMap<UUID, AcousticQuality>()

    internal fun currentListener(): ListenerPose = listenerRef.value
    internal fun currentQuality(): AcousticQuality = qualityRef.value
    internal fun currentBinaural(): Boolean = binauralRef.value
    internal fun currentNormalization(): Boolean = normalizationRef.value

    /** Selects binaural (headphone) rendering vs. constant-power stereo pan for every source. */
    fun setBinauralOutput(binaural: Boolean) {
        binauralRef.value = binaural
    }

    /** Turns loudness normalization on or off for every source: quiet videos are raised and loud ones lowered to one level. */
    fun setLoudnessNormalization(enabled: Boolean) {
        normalizationRef.value = enabled
    }

    override fun registerSource(id: UUID): AudioDspStage =
        chains.computeIfAbsent(id) { AudioRenderChain(sampleRate, this).also { it.qualityOverride = sourceQuality[id] } }

    /**
     * Gives one source its own quality tier ([quality] = null: follow the global tier again). Set before the source
     * is registered it applies from the start; [unregisterSource] forgets it.
     */
    fun setSourceQuality(id: UUID, quality: AcousticQuality?) {
        if (quality == null) sourceQuality.remove(id) else sourceQuality[id] = quality
        chains[id]?.qualityOverride = quality
    }

    /** The tier source [id] renders with: its own, else the global one. */
    fun qualityFor(id: UUID): AcousticQuality = sourceQuality[id] ?: qualityRef.value

    override fun unregisterSource(id: UUID) {
        sourceQuality.remove(id)
        chains.remove(id)?.close()
    }

    override fun updateSource(id: UUID, state: SourceAcousticState) {
        chains[id]?.updateState(state)
    }

    override fun updateListener(pose: ListenerPose) {
        listenerRef.value = pose
    }

    override fun setGlobalQuality(quality: AcousticQuality) {
        qualityRef.value = quality
    }
}
