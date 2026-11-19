package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.platform.client.Config
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayVolumeTest {
    @Test
    fun savedVolumesKeepTheirMenuPercent() {
        // The saved unit is unchanged by the boost: 0.5 is 100 %, 1.0 is 200 %, 2.0 is 400 %
        assertEquals(50, DisplayVolume.percent(0.25))
        assertEquals(100, DisplayVolume.percent(0.5))
        assertEquals(200, DisplayVolume.percent(1.0))
        assertEquals(400, DisplayVolume.percent(2.0))
    }

    @Test
    fun withoutTheBoostTheSliderSpansTheOld0To200() {
        assertEquals(0.5, DisplayVolume.toSlider(0.5f, boost = false), 1e-9)
        assertEquals(1.0, DisplayVolume.toSlider(1f, boost = false), 1e-9)
        assertEquals(1f, DisplayVolume.fromSlider(1.0, boost = false))
        assertEquals(0.25f, DisplayVolume.fromSlider(0.25, boost = false))
        assertEquals(0.025, DisplayVolume.sliderStep(boost = false), 1e-12)
    }

    @Test
    fun withTheBoostTheSliderSpans0To400() {
        // A saved 50 % sits an eighth along the longer track and still reads 50 %
        assertEquals(0.125, DisplayVolume.toSlider(0.25f, boost = true), 1e-9)
        assertEquals(0.5, DisplayVolume.toSlider(1f, boost = true), 1e-9)
        assertEquals(2f, DisplayVolume.fromSlider(1.0, boost = true))
        assertEquals(1.5f, DisplayVolume.fromSlider(0.75, boost = true))
        assertEquals(300, DisplayVolume.percent(DisplayVolume.fromSlider(0.75, boost = true).toDouble()))
        // Stops stay 5 % apart in the label
        val step = DisplayVolume.sliderStep(boost = true)
        assertEquals(5, DisplayVolume.percent(DisplayVolume.fromSlider(step, boost = true).toDouble()))
    }

    @Test
    fun everyValueRoundTripsThroughTheSlider() {
        for (boost in listOf(false, true)) {
            for (p in 0..DisplayVolume.percent(DisplayVolume.max(boost).toDouble()) step 5) {
                val saved = (p / DisplayVolume.PERCENT_PER_UNIT).toFloat()
                val back = DisplayVolume.fromSlider(DisplayVolume.toSlider(saved, boost), boost)
                assertEquals(p, DisplayVolume.percent(back.toDouble()), "boost=$boost $p %")
            }
        }
    }

    @Test
    fun clampFollowsTheSetting() {
        assertEquals(1f, DisplayVolume.clamp(1.6f, boost = false))
        assertEquals(1.6f, DisplayVolume.clamp(1.6f, boost = true))
        assertEquals(2f, DisplayVolume.clamp(3f, boost = true))
        assertEquals(0f, DisplayVolume.clamp(-0.5f, boost = true))
        assertEquals(0f, DisplayVolume.clamp(Float.NaN, boost = false))
        // A saved 300 % on a track without the boost shows at the end of it (200 %)
        assertEquals(1.0, DisplayVolume.toSlider(1.5f, boost = false), 1e-9)
    }

    @Test
    fun arrowKeysStepInFivePercentStopsUpToTheLimit() {
        assertEquals(105, DisplayVolume.percent(DisplayVolume.nudge(0.5f, 0.025, boost = true).toDouble()))
        assertEquals(200, DisplayVolume.percent(DisplayVolume.nudge(1f, 0.025, boost = false).toDouble()))
        assertEquals(205, DisplayVolume.percent(DisplayVolume.nudge(1f, 0.025, boost = true).toDouble()))
        assertEquals(400, DisplayVolume.percent(DisplayVolume.nudge(2f, 0.05, boost = true).toDouble()))
        assertEquals(0, DisplayVolume.percent(DisplayVolume.nudge(0.01f, -0.05, boost = true).toDouble()))
        // An off-stop value snaps onto a stop
        assertEquals(110, DisplayVolume.percent(DisplayVolume.nudge(0.531f, 0.025, boost = false).toDouble()))
    }

    @Test
    fun boostIsOffByDefaultAndSaved() {
        val dir = Files.createTempDirectory("dd-volume-boost").toFile()
        try {
            assertFalse(Config(dir).volumeBoost)
            Config(dir).apply { volumeBoost = true }.save()
            assertTrue(Config(dir).volumeBoost)
        } finally {
            dir.deleteRecursively()
        }
    }
}
