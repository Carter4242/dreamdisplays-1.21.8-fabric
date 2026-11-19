package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import kotlin.math.roundToInt

/**
 * A display's own volume, in the unit the client saves it in: the gain the audio sink multiplies the samples by (before
 * distance, master volume and the server's levers). The menu shows it doubled as a percent: `0.5` is 100 %, `1.0` is
 * 200 % (the stream at its own level). With the "Volume up to 400%" setting (`extended-volume`) it may go to `2.0`
 * (400 %, +6 dB); the sink soft-limits a gain above 1 instead of clipping. Servers never see this setting: their pushed volumes stay within
 * 0..1 and their `audio.maxVolume` still caps a volume above 200 %.
 */
object DisplayVolume {
    /** Menu percent per saved unit. */
    const val PERCENT_PER_UNIT = 200.0

    /** Highest saved volume without the extended range: 200 %. */
    const val NORMAL_MAX = 1f

    /** Highest saved volume with the extended range: 400 %. */
    const val EXTENDED_MAX = 2f

    /** One slider / arrow-key stop in saved units: 5 % in the menu. */
    const val STEP = 0.025

    /** The player's "Volume up to 400%" setting. */
    val extended: Boolean get() = ClientStateManager.config.extendedVolume

    /** Highest saved volume allowed with [extended] on or off. */
    fun max(extended: Boolean): Float = if (extended) EXTENDED_MAX else NORMAL_MAX

    /** [volume] within 0..[max]; NaN is silence. */
    fun clamp(volume: Float, extended: Boolean): Float = if (volume.isNaN()) 0f else volume.coerceIn(0f, max(extended))

    /** The menu percent for a saved [volume]. */
    fun percent(volume: Double): Int = (volume * PERCENT_PER_UNIT).roundToInt()

    /** The volume slider's 0..1 position for a saved [volume]: its track spans 0..[max]. */
    fun toSlider(volume: Float, extended: Boolean): Double = (clamp(volume, extended) / max(extended)).toDouble()

    /** The saved volume at slider position [fraction] (0..1). */
    fun fromSlider(fraction: Double, extended: Boolean): Float = clamp((fraction * max(extended)).toFloat(), extended)

    /** The slider's snap step (a fraction of its track) that lands on 5 % stops. */
    fun sliderStep(extended: Boolean): Double = STEP / max(extended)

    /** [volume] moved by [delta] (saved units) and snapped to a 5 % stop, within the allowed range. */
    fun nudge(volume: Float, delta: Double, extended: Boolean): Float =
        clamp((((volume + delta) / STEP).roundToInt() * STEP).toFloat(), extended)

    /**
     * Brings every display's volume back within 200 % after the extended range was turned off: loaded displays through
     * their screen (heard at once and saved), every other saved display in its settings. Render thread.
     */
    fun applyLimit(extended: Boolean = this.extended) {
        if (extended) return
        DisplayRegistry.screens.values.forEach { screen ->
            if (screen.volume > NORMAL_MAX) screen.volume = NORMAL_MAX
        }
        ClientSettingsStore.capVolumes(NORMAL_MAX)
    }
}
