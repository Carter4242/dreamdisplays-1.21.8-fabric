package com.dreamdisplays.core.karaoke

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.toSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KaraokeTimingTest {
    private val latency = KaraokeLatency(singerOutputCaptureMs = 60, listenerBufferOutputMs = 120)

    @Test
    fun emitDelayFollowsTheFormula() {
        // lead 350 - (60 + 60/2) - (100/2 + 120) + trim 10 = 350 - 90 - 170 + 10 = 100
        assertEquals(100L, KaraokeTiming.emitDelayMs(350, 60, 100, 10, latency))
    }

    @Test
    fun emitDelayGoesNegativeWhenTheLeadIsTooSmall() {
        assertTrue(KaraokeTiming.emitDelayMs(200, 100, 200, 0, latency) < 0)
    }

    @Test
    fun aFrameEmittedAtTheDelayLandsOnTheBeat() {
        val r = 100_000L // R(m): when track moment m plays for every listener
        val lead = 330L
        val singerPing = 80L
        val listenerPing = 140L
        val arrival = r - lead + latency.singerOutputCaptureMs + singerPing / 2
        val emit = arrival + KaraokeTiming.emitDelayMs(lead, singerPing, listenerPing, 0, latency)
        val heard = emit + listenerPing / 2 + latency.listenerBufferOutputMs
        assertEquals(r, heard)
    }

    @Test
    fun autoLeadCoversTheSlowestListenerAndIsClamped() {
        // 40/2 + 60 + 100/2 + 120 + 40 = 290
        assertEquals(290L, KaraokeTiming.autoLeadMs(40, 100, latency, 40, 100, 1_500))
        assertEquals(260L, KaraokeTiming.autoLeadMs(0, 0, latency, 0, 260, 1_500))
        assertEquals(500L, KaraokeTiming.autoLeadMs(2_000, 2_000, latency, 40, 100, 500))
        // The automatic lead leaves exactly the margin for that listener
        val lead = KaraokeTiming.autoLeadMs(40, 100, latency, 40, 0, 3_000)
        assertEquals(40L, KaraokeTiming.emitDelayMs(lead, 40, 100, 0, latency))
    }

    @Test
    fun aSingerGetsThePositionAheadAndOthersGetItUnchanged() {
        val timeline = Timeline(positionMs = 10_000, serverTimeMs = 50_000, paused = false, durationMs = 200_000, loop = true)
        val sync = timeline.toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 51_000)
        val singer = KaraokeTiming.withLead(sync, 350)
        assertEquals(sync.currentTimeMs + 350, singer.currentTimeMs)
        assertEquals(sync.serverTimeMs, singer.serverTimeMs)
        assertEquals(sync.isPaused, singer.isPaused)
        assertSame(sync, KaraokeTiming.withLead(sync, 0))
    }

    @Test
    fun aPausedDisplayShowsTheSingerThePausedFramePlusTheLead() {
        val paused = Timeline(positionMs = 42_000, serverTimeMs = 1_000, paused = true, durationMs = 100_000, loop = true)
        val sync = KaraokeTiming.withLead(paused.toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 9_000), 300)
        assertTrue(sync.isPaused)
        assertEquals(42_300L, sync.currentTimeMs)
    }

    @Test
    fun theLeadWrapsALoopingVideoAndStopsAtTheEndOfAFiniteOne() {
        val looping = Timeline(positionMs = 99_900, serverTimeMs = 0, paused = true, durationMs = 100_000, loop = true)
        assertEquals(200L, KaraokeTiming.withLead(looping.toSync(ZERO_UUID, PlaybackMode.SYNCED, 0), 300).currentTimeMs)
        val finite = looping.copy(loop = false)
        assertEquals(100_000L, KaraokeTiming.withLead(finite.toSync(ZERO_UUID, PlaybackMode.SYNCED, 0), 300).currentTimeMs)
        val unknown = looping.copy(durationMs = 0)
        assertEquals(100_200L, KaraokeTiming.withLead(unknown.toSync(ZERO_UUID, PlaybackMode.SYNCED, 0), 300).currentTimeMs)
    }

    @Test
    fun theLeadIsWallTimeSoItScalesWithSpeed() {
        assertEquals(1_600L, KaraokeTiming.leadPosition(1_000, 400, 1.5, 0, false))
        assertEquals(1_200L, KaraokeTiming.leadPosition(1_000, 400, 0.5, 0, false))
    }

    @Test
    fun aScheduledStartStartsTheSingerAheadAtTheSameMoment() {
        val scheduled = Timeline(positionMs = 0, serverTimeMs = 10_150, paused = false, durationMs = 60_000, loop = true)
        val sync = scheduled.toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 10_000)
        val singer = KaraokeTiming.withLead(sync, 300)
        assertEquals(sync.startInMs, singer.startInMs)
        assertEquals(300L, singer.currentTimeMs)
        assertFalse(singer.isPaused)
    }

    @Test
    fun aSpeedChangeAndAReadinessGatedStartKeepTheSingerOffset() {
        val lead = 300L
        // Playing at 1x, switched to 4x and back: held paused at the position, then a scheduled start
        var t = Timeline(positionMs = 5_000, serverTimeMs = 0, paused = false, durationMs = 600_000, loop = true)
        t = t.withRate(4.0, nowMs = 1_000).withPaused(true, nowMs = 1_000)
        val held4x = t.toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 1_500)
        assertEquals(held4x.currentTimeMs + lead * 4, KaraokeTiming.withLead(held4x, lead).currentTimeMs)
        t = t.withPaused(false, nowMs = 2_000).withRate(1.0, nowMs = 3_000).withPaused(true, nowMs = 3_000)
        val held = t.toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 3_100)
        assertEquals(held.currentTimeMs + lead, KaraokeTiming.withLead(held, lead).currentTimeMs)
        // Every viewer ready: start 150 ms ahead on the same frame
        val start = t.copy(paused = false, serverTimeMs = 3_250).toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 3_100)
        val singer = KaraokeTiming.withLead(start, lead)
        assertEquals(start.currentTimeMs + lead, singer.currentTimeMs)
        assertEquals(start.startInMs, singer.startInMs)
        assertEquals(1.0, singer.rate)
        // And once it runs
        val running = t.copy(paused = false, serverTimeMs = 3_250).toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 4_000)
        assertEquals(running.currentTimeMs + lead, KaraokeTiming.withLead(running, lead).currentTimeMs)
    }
}
