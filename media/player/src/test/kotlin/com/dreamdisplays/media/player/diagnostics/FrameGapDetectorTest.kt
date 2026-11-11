package com.dreamdisplays.media.player.diagnostics

import com.dreamdisplays.media.runtime.diagnostics.FrameGapDetector
import com.dreamdisplays.media.runtime.diagnostics.GcPauses
import com.dreamdisplays.media.runtime.diagnostics.HitchClassifier
import com.dreamdisplays.media.runtime.diagnostics.HitchSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameGapDetectorTest {
    private val ms = 1_000_000L

    /** Draws at [gameFrameMs] for [frames] frames, presenting a video frame every [presentEvery]th draw. */
    private fun FrameGapDetector.run(
        startMs: Long, frames: Int, gameFrameMs: Long, presentEvery: Int, videoMs: Long, hits: MutableList<Long>,
    ): Long {
        var t = startMs
        repeat(frames) { i ->
            t += gameFrameMs
            if (onDraw(t * ms, i % presentEvery == 0, 1 * ms, true, videoMs * ms)) hits += gapNanos / ms
        }
        return t
    }

    @Test
    fun `steady playback has no hitches, also with the game slower than the video`() {
        val hits = mutableListOf<Long>()
        // 60 fps game, 30 fps video
        FrameGapDetector().run(0, 600, 16, 2, 33, hits)
        // 30 fps game, 60 fps video: every presented frame is 33 ms apart, which is the game's own pace
        FrameGapDetector().run(0, 600, 33, 1, 16, hits)
        assertTrue(hits.isEmpty(), "$hits")
    }

    @Test
    fun `a few missing frames count, one does not`() {
        val d = FrameGapDetector()
        val hits = mutableListOf<Long>()
        var t = d.run(0, 120, 16, 1, 16, hits)
        // One frame late (32 ms): below 2 intervals and the 40 ms floor
        t += 16; assertFalse(d.onDraw(t * ms, false, ms, true, 16 * ms))
        t += 16; assertFalse(d.onDraw(t * ms, true, ms, true, 16 * ms))
        // Five frames without a new picture (~96 ms): a stutter
        repeat(5) { t += 16; assertFalse(d.onDraw(t * ms, false, ms, true, 16 * ms)) }
        t += 16
        assertTrue(d.onDraw(t * ms, true, ms, true, 16 * ms))
        assertEquals(96L, d.gapNanos / ms)
        assertEquals(16L, d.worstGameFrameNanos / ms)
    }

    @Test
    fun `a game stall shows up as a long draw interval`() {
        val d = FrameGapDetector()
        val hits = mutableListOf<Long>()
        var t = d.run(0, 120, 16, 1, 16, hits)
        t += 180
        assertTrue(d.onDraw(t * ms, true, ms, true, 16 * ms))
        assertEquals(180L, d.worstGameFrameNanos / ms)
    }

    @Test
    fun `paused, stopped clock or hidden display is not a stutter`() {
        val d = FrameGapDetector()
        val hits = mutableListOf<Long>()
        var t = d.run(0, 60, 16, 1, 16, hits)
        repeat(20) { t += 16; d.onDraw(t * ms, false, ms, false, 16 * ms) }
        t += 16
        assertFalse(d.onDraw(t * ms, true, ms, true, 16 * ms))
        // Not drawn for 1.5 s (off screen), then back
        t += 1_500
        assertFalse(d.onDraw(t * ms, true, ms, true, 16 * ms))
        d.hidden()
        t += 400
        assertFalse(d.onDraw(t * ms, true, ms, true, 16 * ms))
    }

    private fun sample(
        gap: Long = 120, gameFrame: Long = 16, gc: Long = 0, upload: Long = 1, starved: Boolean = false,
        late: Boolean = false, underrun: Boolean = false, net: Long = -1, sync: String? = null,
    ) = HitchSample(gap, 16, gameFrame, 16, gc, if (gc > 0) "G1 Young Generation ${gc}ms" else null, upload, starved, late,
        3, underrun, 180, net, sync, "native-gpuyuv/d3d11va")

    @Test
    fun `classifier picks the strongest signal`() {
        assertEquals(HitchClassifier.GC, HitchClassifier.classify(sample(gap = 160, gameFrame = 150, gc = 140)))
        assertEquals(HitchClassifier.GAME_STALL, HitchClassifier.classify(sample(gap = 160, gameFrame = 150)))
        assertEquals(HitchClassifier.SYNC_SEEK, HitchClassifier.classify(sample(sync = "seek")))
        assertEquals(HitchClassifier.UPLOAD, HitchClassifier.classify(sample(upload = 70)))
        assertEquals(HitchClassifier.NETWORK_STARVED, HitchClassifier.classify(sample(starved = true, net = 10_000)))
        assertEquals(HitchClassifier.DECODE, HitchClassifier.classify(sample(starved = true, net = 8_000_000)))
        assertEquals(HitchClassifier.DECODE, HitchClassifier.classify(sample(late = true)))
        assertEquals(HitchClassifier.AUDIO, HitchClassifier.classify(sample(underrun = true)))
        assertEquals(HitchClassifier.UNKNOWN, HitchClassifier.classify(sample()))
        // A short GC inside a long gap is not the cause
        assertEquals(HitchClassifier.DECODE, HitchClassifier.classify(sample(gap = 300, gc = 5, starved = true)))
    }

    @Test
    fun `log line and metrics carry the figures`() {
        val s = sample(gap = 180, gameFrame = 150, gc = 140)
        val line = HitchClassifier.logLine("5f1c2a9e", HitchClassifier.classify(s), s)
        assertTrue(line.startsWith("[dd-hitch] 5f1c2a9e gap=180ms cause=gc(G1 Young Generation 140ms) mc_frame=150ms"), line)
        assertTrue("pipeline=native-gpuyuv/d3d11va" in line)
        val m = HitchClassifier.metrics(s)
        assertEquals("140", m["gc_ms"])
        assertEquals("3", m["decode_q"])
        assertNull(m["net_buf_kib"])
    }

    @Test
    fun `gc pauses overlapping a window are found`() {
        GcPauses.record(1_000 * ms, 100, "G1 Young Generation")
        val (total, worst) = GcPauses.overlapping(850 * ms, 1_200 * ms)
        assertEquals(100L, total)
        assertEquals("G1 Young Generation 100ms", worst)
        assertEquals(50L, GcPauses.overlapping(950 * ms, 1_200 * ms).first)
        assertEquals(0L, GcPauses.overlapping(2_000 * ms, 3_000 * ms).first)
    }
}
