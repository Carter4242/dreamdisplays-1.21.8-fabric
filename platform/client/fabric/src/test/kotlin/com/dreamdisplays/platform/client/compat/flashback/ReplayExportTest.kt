package com.dreamdisplays.platform.client.compat.flashback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayExportTest {
    @Test
    fun `each frame gets exactly the samples Flashback renders for it, none lost over time`() {
        var carry = 0.0
        var total = 0L
        val counts = HashSet<Int>()
        repeat(300) {
            val (n, c) = ReplayExport.samplesForFrame(carry, 59.94)
            carry = c
            total += n
            counts += n
        }
        assertEquals((300 * 48_000 / 59.94).toLong(), total)
        assertTrue(counts.all { it == 800 || it == 801 })
    }

    @Test
    fun `a frame's sound covers the content the replay moves on by, nothing while paused`() {
        val playing = ReplaySample(1, 10_000, 0, 2.0, paused = false, loop = false, durationMs = 60_000)
        val frameMs = 1000.0 / 30
        // First frame: a frame's length at the timeline's rate
        val from = playing.positionNanosAt(250.0)
        assertEquals(10_500_000_000L, from)
        assertEquals(from + 66_666_666L, ReplayExport.endOfFrame(playing, from, null, frameMs))
        // A speed keyframe doubled the replay's pace: the frame before moved 133 ms, so does this one
        assertEquals(from + 133_333_332L, ReplayExport.endOfFrame(playing, from, from - 133_333_332L, frameMs))
        // A jump: back to a frame's length
        assertEquals(from + 66_666_666L, ReplayExport.endOfFrame(playing, from, from - 9_000_000_000L, frameMs))
        val paused = playing.copy(paused = true)
        assertEquals(from, ReplayExport.endOfFrame(paused, from, from - 1, frameMs))
    }

    @Test
    fun `export positions are exact between whole milliseconds`() {
        val s = ReplaySample(1, 0, 0, 1.0, paused = false, loop = false, durationMs = 60_000)
        assertEquals(33_333_333L, s.positionNanosAt(1000.0 / 30))
        assertEquals(s.positionAt(2_000) * 1_000_000L, s.positionNanosAt(2_000.0))
    }

    @Test
    fun `sound at another speed is stretched to the frame's samples`() {
        val content = FloatArray(1600) { it.toFloat() }
        val out = FloatArray(800)
        ExportSound.resample(content, out)
        assertEquals(0f, out.first())
        assertEquals(1599f, out.last())
        val same = FloatArray(1600)
        ExportSound.resample(content, same)
        assertTrue(same.contentEquals(content))
    }
}
