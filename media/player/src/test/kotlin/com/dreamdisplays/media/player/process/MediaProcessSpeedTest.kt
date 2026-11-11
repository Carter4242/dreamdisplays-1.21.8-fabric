package com.dreamdisplays.media.player.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaProcessSpeedTest {
    private fun stages(filter: String): List<Double> =
        filter.split(',').map { stage ->
            assertTrue(stage.startsWith("atempo="), "Only atempo (pitch-preserving) stages, got $stage")
            stage.removePrefix("atempo=").toDouble()
        }

    @Test
    fun `normal speed adds no filter`() {
        assertNull(MediaProcess.tempoFilter(1.0))
        assertFalse("-af" in MediaProcess.audioOutputArgs(44_100))
    }

    @Test
    fun `every speed is a chain of clean atempo stages whose product is the speed`() {
        for (tempo in listOf(0.1, 0.25, 0.3, 0.5, 0.75, 1.25, 1.5, 2.0, 3.0, 4.0, 7.5, 10.0)) {
            val s = stages(MediaProcess.tempoFilter(tempo)!!)
            s.forEach { assertTrue(it in 0.5..2.0, "Stage $it of ${tempo}x is outside atempo's clean range") }
            assertEquals(tempo, s.fold(1.0) { a, b -> a * b }, 1e-6, "Stages of ${tempo}x")
        }
        assertEquals(["atempo=2.000000", "atempo=2.000000", "atempo=2.000000", "atempo=1.250000"],
            MediaProcess.tempoFilter(10.0)!!.split(','))
        assertEquals(["atempo=0.500000", "atempo=0.500000", "atempo=0.500000", "atempo=0.800000"],
            MediaProcess.tempoFilter(0.1)!!.split(','))
        assertEquals("atempo=1.500000", MediaProcess.tempoFilter(1.5))
    }

    @Test
    fun `audio args put the tempo filter before the raw pcm output`() {
        val a = MediaProcess.audioOutputArgs(44_100, 1.5)
        assertEquals(["-vn", "-af", "atempo=1.500000", "-f", "s16le", "-ar", "44100", "-ac", "2", "-"], a)
    }

    @Test
    fun `fast sessions thin frames to sixty a second`() {
        assertEquals(30.0, MediaProcess.speedFps(30.0, 1.0))
        assertEquals(30.0, MediaProcess.speedFps(30.0, 0.1))
        assertEquals(30.0, MediaProcess.speedFps(30.0, 2.0))
        assertEquals(40.0, MediaProcess.speedFps(60.0, 1.5))
        assertEquals(6.0, MediaProcess.speedFps(30.0, 10.0))
    }

    @Test
    fun `dropped frames are dropped before scaling`() {
        val args = MediaProcess.videoArgs(
            "FFMPEG", "https://cdn.example.com/movie.mp4", 640, 360, 0L, HwAccelBackend.NONE,
            MediaProcess.VideoTransport.RAW_NV12, 6.0, alreadyResolved = true, dropToFps = true,
        )
        val vf = args[args.indexOf("-vf") + 1]
        assertTrue(vf.startsWith("fps=6.000000,scale="), vf)
        assertEquals("6.000000", args[args.indexOf("-r") + 1])

        val normal = MediaProcess.videoArgs(
            "FFMPEG", "https://cdn.example.com/movie.mp4", 640, 360, 0L, HwAccelBackend.NONE,
            MediaProcess.VideoTransport.RAW_NV12, 30.0, alreadyResolved = true,
        )
        assertFalse(normal[normal.indexOf("-vf") + 1].startsWith("fps="))
    }
}
