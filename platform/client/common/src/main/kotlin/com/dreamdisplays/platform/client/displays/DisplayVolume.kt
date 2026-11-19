package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import kotlin.math.roundToInt

/**
 * A display's own volume, in the unit the client saves it in: the gain the audio sink multiplies the samples by (before
 * distance, master volume and the server's levers). The menu shows it doubled as a percent: `0.5` is 100 %, `1.0` is
 * 200 % (the stream at its own level). With the "Volume boost" setting it may go to `2.0` (400 %, +6 dB); the sink
 * soft-limits a gain above 1 instead of clipping. Servers never see this setting: their pushed volumes stay within
 * 0..1 and their `audio.maxVolume` still caps a boosted volume.
 */
object DisplayVolume {
    /** Menu percent per saved unit. */
    const val PERCENT_PER_UNIT = 200.0

    /** Highest saved volume without the boost: 200 %. */
    const val NORMAL_MAX = 1f

    /** Highest saved volume with the boost: 400 %. */
    const val BOOST_MAX = 2f

    /** One slider / arrow-key stop in saved units: 5 % in the menu. */
    const val STEP = 0.025

    /** The player's "Volume boost" setting. */
    val boost: Boolean get() = ClientStateManager.config.volumeBoost

    /** Highest saved volume allowed with [boost] on or off. */
    fun max(boost: Boolean): Float = if (boost) BOOST_MAX else NORMAL_MAX

    /** [volume] within 0..[max]; NaN is silence. */
    fun clamp(volume: Float, boost: Boolean): Float = if (volume.isNaN()) 0f else volume.coerceIn(0f, max(boost))

    /** The menu percent for a saved [volume]. */
    fun percent(volume: Double): Int = (volume * PERCENT_PER_UNIT).roundToInt()

    /** The volume slider's 0..1 position for a saved [volume]: its track spans 0..[max]. */
    fun toSlider(volume: Float, boost: Boolean): Double = (clamp(volume, boost) / max(boost)).toDouble()

    /** The saved volume at slider position [fraction] (0..1). */
    fun fromSlider(fraction: Double, boost: Boolean): Float = clamp((fraction * max(boost)).toFloat(), boost)

    /** The slider's snap step (a fraction of its track) that lands on 5 % stops. */
    fun sliderStep(boost: Boolean): Double = STEP / max(boost)

    /** [volume] moved by [delta] (saved units) and snapped to a 5 % stop, within the allowed range. */
    fun nudge(volume: Float, delta: Double, boost: Boolean): Float =
        clamp((((volume + delta) / STEP).roundToInt() * STEP).toFloat(), boost)

    /**
     * Brings every display's volume back within 200 % after the boost was turned off: loaded displays through their
     * screen (heard at once and saved), every other saved display in its settings. Render thread.
     */
    fun applyLimit(boost: Boolean = this.boost) {
        if (boost) return
        DisplayRegistry.screens.values.forEach { screen ->
            if (screen.volume > NORMAL_MAX) screen.volume = NORMAL_MAX
        }
        ClientSettingsStore.capVolumes(NORMAL_MAX)
    }
}
