package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlphaVideoTest {
    private fun stream(codec: String?, alpha: Boolean) = MediaStream(
        url = "https://cdn.example.com/clip.webm", type = MediaStreamType.VIDEO_AUDIO, codec = codec,
        width = null, height = null, fps = null, bitrate = null, audioTrackName = null, audioTrackLang = null,
        hasAlpha = alpha,
    )

    private val everyDecoder: (String) -> Boolean = { true }

    @Test
    fun `vp8 and vp9 alpha are forced onto libvpx`() {
        assertEquals("libvpx-vp9", AlphaVideo.plan(stream("vp9", true), true, everyDecoder)?.decoder)
        assertEquals("libvpx", AlphaVideo.plan(stream("vp8", true), true, everyDecoder)?.decoder)
    }

    @Test
    fun `anything that cannot keep its alpha plays opaque`() {
        assertNull(AlphaVideo.plan(stream("vp9", false), true, everyDecoder), "An opaque video stays on its path.")
        assertNull(AlphaVideo.plan(stream("vp9", true), false, everyDecoder), "Transparency is switched off.")
        assertNull(AlphaVideo.plan(stream("vp9", true), true) { false }, "This FFmpeg has no libvpx.")
        assertNull(AlphaVideo.plan(stream(null, true), true, everyDecoder), "Unknown codec.")
    }

    @Test
    fun `decode size is capped to the alpha maximum and stays even`() {
        assertEquals(1280 to 720, AlphaVideo.capDims(1920, 1080))
        assertEquals(854 to 480, AlphaVideo.capDims(854, 480))
        val (w, h) = AlphaVideo.capDims(3413, 1440)
        assertEquals(AlphaVideo.MAX_HEIGHT, h)
        assertEquals(0, w and 1)
        // The texture and the decoder each cap the same size, sometimes an already capped one
        assertEquals(AlphaVideo.capDims(1920, 1080), AlphaVideo.capDims(1280, 720))
        assertEquals(AlphaVideo.capDims(3413, 1440), AlphaVideo.capDims(w, h))
    }

    @Test
    fun `alpha args force the decoder before the input and emit transparent rgba`() {
        val a = MediaProcess.alphaVideoArgs(
            "FFMPEG", "https://cdn.example.com/clip.webm", 1280, 720, 5_000_000_000L, "libvpx-vp9", 30.0,
        )
        val decoderAt = a.indexOf("-c:v")
        assertTrue(decoderAt >= 0 && decoderAt < a.indexOf("-i"), "A decoder choice is an input option.")
        assertEquals("libvpx-vp9", a[decoderAt + 1])
        assertTrue(a.indexOf("-ss") < a.indexOf("-i"))
        assertTrue("-hwaccel" !in a, "Hardware decoders drop the alpha plane.")
        val vf = a[a.indexOf("-vf") + 1]
        assertTrue(vf.endsWith("format=rgba"), vf)
        assertTrue("color=black@0" in vf, "Letterbox bars must be transparent: $vf")
        assertEquals(["-f", "rawvideo", "-"], a.takeLast(3))
    }

    @Test
    fun `decoder listing is parsed by name`() {
        val listing = """
            Decoders:
             V..... = Video
             ------
             VFS..D vp9                  Google VP9
             V..... libvpx-vp9           libvpx VP9 (codec vp9)
             V....D libvpx               libvpx VP8 (codec vp8)
        """.trimIndent()
        assertEquals(setOf("libvpx-vp9", "libvpx"), FFmpegCapabilities.parseDecoders(listing, setOf("libvpx-vp9", "libvpx")))
        assertEquals(emptySet(), FFmpegCapabilities.parseDecoders(" VFS..D vp9  Google VP9", setOf("libvpx-vp9")))
    }
}
