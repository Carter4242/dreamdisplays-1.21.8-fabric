package com.dreamdisplays.media.player

import kotlin.math.abs

/** Combines the user-set volume with distance-based attenuation and pushes the effective value to the audio pipeline. */
internal class VolumeController(
    initialVolume: Double,
    private val applyVolume: (Double) -> Unit,
) {
    @Volatile
    private var userVolume = initialVolume

    @Volatile
    private var lastAttenuation = 1.0

    /** Sets the user-controlled volume (clamped to 0.0-2.0) and re-applies the effective value. */
    fun setUserVolume(volume: Float) {
        userVolume = volume.toDouble().coerceIn(0.0, 2.0)
        applyVolume(userVolume * lastAttenuation)
    }

    /**
     * Recomputes distance attenuation for [distance] and re-applies the effective volume only when it changed
     * materially: full volume within [fullVolumeRadius], then fading to silence at [maxRadius], quadratically
     * when [squared] (the default) or linearly otherwise.
     */
    fun updateAttenuation(distance: Double, maxRadius: Double, fullVolumeRadius: Double = 0.0, squared: Boolean = true) {
        val attenuation = attenuation(distance, maxRadius, fullVolumeRadius, squared)
        if (abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation
            applyVolume(userVolume * attenuation)
        }
    }

    internal companion object {
        /** Volume multiplier (0..1) at [distance]; see [updateAttenuation]. */
        fun attenuation(distance: Double, maxRadius: Double, fullVolumeRadius: Double = 0.0, squared: Boolean = true): Double {
            val full = fullVolumeRadius.coerceAtLeast(0.0)
            if (distance <= full) return 1.0
            val span = maxRadius - full
            if (span <= 0.0) return 0.0
            val remaining = 1.0 - ((distance - full) / span).coerceIn(0.0, 1.0)
            return if (squared) remaining * remaining else remaining
        }
    }
}
