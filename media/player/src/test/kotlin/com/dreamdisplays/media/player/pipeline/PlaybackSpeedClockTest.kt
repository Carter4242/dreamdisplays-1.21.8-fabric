package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackSpeedClockTest {
    private val second = 1_000_000_000L

    @Test
    fun `a sped-up clock advances content at its rate`() {
        val clock = PlaybackClock()
        clock.reset(10 * second)
        clock.setRate(4.0)
        clock.markFirstFrame()
        val wallStart = System.nanoTime()
        Thread.sleep(50)
        val wall = System.nanoTime() - wallStart
        val content = clock.currentTime() - 10 * second
        assertTrue(content >= wall * 3 && content <= (wall + 50_000_000L) * 4, "content=$content wall=$wall")
    }

    @Test
    fun `changing the rate of a running clock keeps it continuous`() {
        val clock = PlaybackClock()
        clock.reset(0)
        clock.markFirstFrame()
        Thread.sleep(20)
        val before = clock.currentTime()
        clock.setRate(0.1)
        val after = clock.currentTime()
        assertTrue(after - before in 0L..5_000_000L, "jumped by ${after - before} ns")
    }

    @Test
    fun `pcm of a time-stretched process maps to content at the tempo`() {
        assertEquals(2 * second, AudioSink.pcmToContentNanos(second, 2.0))
        assertEquals(second / 10, AudioSink.pcmToContentNanos(second, 0.1))
        assertEquals(second, AudioSink.pcmToContentNanos(second, 1.0))
        assertEquals(second / 2, AudioSink.contentToPcmNanos(second, 2.0))
        assertEquals(10 * second, AudioSink.contentToPcmNanos(second, 0.1))
    }

    @Test
    fun `the master clock fills in between line steps at the playback speed`() {
        var now = 0L
        var rate = 3.0
        val master = AudioMasterClock("test", nowNanos = { now }, rate = { rate })
        val sample = AudioSink.ClockSample(5 * second, 1, originKnown = true)
        master.nanos(sample, -1L, suspended = false) { null }
        // A line that steps every 20 ms: the next read 10 ms after a step is 30 ms of content further
        now += 20_000_000L
        master.nanos(AudioSink.ClockSample(5 * second + 60_000_000L, 1, true), -1L, false) { null }
        now += 10_000_000L
        val out = master.nanos(AudioSink.ClockSample(5 * second + 60_000_000L, 1, true), -1L, false) { null }
        assertEquals(5 * second + 90_000_000L, out)
    }
}
