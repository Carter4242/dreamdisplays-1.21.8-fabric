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
    fun `a frame covers its length of content at the timeline's speed, nothing while paused`() {
        val playing = ReplaySample(1, 10_000, 0, 2.0, paused = false, loop = false, durationMs = 60_000)
        assertEquals(10_500_000_000L to 10_566_666_666L, ReplayExport.contentOfFrame(playing, 250, 1000.0 / 30))
        val paused = playing.copy(paused = true)
        assertEquals(10_000_000_000L to 10_000_000_000L, ReplayExport.contentOfFrame(paused, 250, 1000.0 / 30))
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
