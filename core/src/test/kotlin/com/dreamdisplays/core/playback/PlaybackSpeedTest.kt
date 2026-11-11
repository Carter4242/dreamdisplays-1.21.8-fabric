package com.dreamdisplays.core.playback

import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSpeedTest {
    @Test
    fun runningTimelineAdvancesAtItsRate() {
        val t = Timeline(positionMs = 10_000, serverTimeMs = 0, paused = false, rate = 1.5)
        assertEquals(10_000, t.positionAt(0))
        assertEquals(11_500, t.positionAt(1_000))
        assertEquals(10_250, Timeline(10_000, 0, paused = false, rate = 0.25).positionAt(1_000))
        assertEquals(20_000, Timeline(10_000, 0, paused = false, rate = 10.0).positionAt(1_000))
    }

    @Test
    fun pausedTimelineIgnoresRate() {
        assertEquals(5_000, Timeline(5_000, 0, paused = true, rate = 2.0).positionAt(60_000))
    }

    @Test
    fun loopWrapsAtRate() {
        val t = Timeline(0, 0, paused = false, durationMs = 1_000, loop = true, rate = 2.0)
        assertEquals(400, t.positionAt(1_200)) // 2 400 ms of content into a 1 s loop
    }

    @Test
    fun withRateKeepsPositionContinuous() {
        val t = Timeline(0, 0, paused = false)
        val faster = t.withRate(2.0, nowMs = 3_000)
        assertEquals(3_000, faster.positionAt(3_000))
        assertEquals(5_000, faster.positionAt(4_000))
        val slower = faster.withRate(0.5, nowMs = 4_000)
        assertEquals(5_000, slower.positionAt(4_000))
        assertEquals(5_500, slower.positionAt(5_000))
    }

    @Test
    fun pauseAndSeekKeepRate() {
        val t = Timeline(0, 0, paused = false, rate = 2.0)
        val paused = t.withPaused(true, 1_000)
        assertEquals(2_000, paused.positionMs)
        assertEquals(2.0, paused.rate)
        val resumed = paused.withPaused(false, 5_000)
        assertEquals(2_000, resumed.positionAt(5_000))
        assertEquals(4_000, resumed.positionAt(6_000))
        assertEquals(2.0, resumed.seekedTo(100, 6_000).rate)
    }

    @Test
    fun clampKeepsSpeedsInRange() {
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.clamp(0.01))
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.clamp(50.0))
        assertEquals(1.0, PlaybackSpeed.clamp(Double.NaN))
        assertEquals(1.0, PlaybackSpeed.clamp(-2.0))
        assertEquals(1.33, PlaybackSpeed.clamp(1.3333))
    }

    @Test
    fun parseAcceptsTypedForms() {
        assertEquals(1.5, PlaybackSpeed.parse("1.5"))
        assertEquals(1.5, PlaybackSpeed.parse(" 1,5 "))
        assertEquals(2.0, PlaybackSpeed.parse("2x"))
        assertEquals(10.0, PlaybackSpeed.parse("25"))
        assertEquals(0.1, PlaybackSpeed.parse("0.01"))
        assertNull(PlaybackSpeed.parse(""))
        assertNull(PlaybackSpeed.parse("abc"))
        assertNull(PlaybackSpeed.parse("0"))
        assertNull(PlaybackSpeed.parse("1.2.3"))
    }

    @Test
    fun formatShowsOneDecimalForWholeSpeeds() {
        assertEquals("1.0", PlaybackSpeed.format(1.0))
        assertEquals("10.0", PlaybackSpeed.format(10.0))
        assertEquals("0.1", PlaybackSpeed.format(0.1))
        assertEquals("1.25", PlaybackSpeed.format(1.25))
        assertEquals("0.75", PlaybackSpeed.format(0.75))
    }
}
