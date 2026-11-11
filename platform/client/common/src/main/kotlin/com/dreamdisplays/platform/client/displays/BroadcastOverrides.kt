package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.model.VideoQuality

/**
 * The volume and quality a fullscreen broadcast plays a display at, over the viewer's own settings for it. Never
 * saved: they last while the broadcast shows, and the viewer moving a setting during it takes that setting back.
 */
internal class BroadcastOverrides {
    var volume: Float? = null; private set
    var quality: VideoQuality? = null; private set

    /**
     * Takes a broadcast's [volume] (negative = the viewer's own) and [quality] (empty = the viewer's own). Returns
     * which of the two changed.
     */
    fun apply(volume: Float, quality: String): Changed {
        val v = volume.takeIf { it >= 0f }?.coerceIn(0f, 1f)
        val q = quality.takeIf { it.isNotEmpty() }?.let(VideoQuality::parse)
        val changed = Changed(v != this.volume, q != this.quality)
        this.volume = v
        this.quality = q
        return changed
    }

    /** The broadcast ended (or its overlay closed): back to the viewer's settings. Returns which of the two changed. */
    fun clear(): Changed {
        val changed = Changed(volume != null, quality != null)
        volume = null
        quality = null
        return changed
    }

    /** The viewer set the volume themselves: it wins from now on. */
    fun onViewerVolume() {
        volume = null
    }

    /** The viewer picked a quality themselves: it wins from now on. */
    fun onViewerQuality() {
        quality = null
    }

    /** The volume to play at, given the viewer's own [own]. */
    fun volumeOver(own: Float): Float = volume ?: own

    /** The quality to request, given the viewer's own [own]. */
    fun qualityOver(own: VideoQuality): VideoQuality = quality ?: own

    data class Changed(val volume: Boolean, val quality: Boolean)
}
