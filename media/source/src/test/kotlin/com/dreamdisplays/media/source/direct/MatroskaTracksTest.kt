package com.dreamdisplays.media.source.direct

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebM alpha is announced only by the video track's `AlphaMode`; reading it wrong either loses the transparency
 * (alpha video drawn opaque) or, worse, sends an ordinary video down the slower alpha path.
 */
class MatroskaTracksTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/matroska/$name")) { "missing fixture $name" }.use { it.readBytes() }

    @Test
    fun `ffmpeg vp9 alpha header is read as alpha`() {
        val video = assertNotNull(MatroskaTracks.firstVideo(resource("alpha_vp9_head.bin")))
        assertEquals("V_VP9", video.codecId)
        assertEquals("vp9", video.codec)
        assertTrue(video.hasAlpha)
    }

    @Test
    fun `ffmpeg vp8 alpha header is read as alpha`() {
        val video = assertNotNull(MatroskaTracks.firstVideo(resource("alpha_vp8_head.bin")))
        assertEquals("vp8", video.codec)
        assertTrue(video.hasAlpha)
    }

    @Test
    fun `an ordinary vp9 webm has no alpha`() {
        val video = assertNotNull(MatroskaTracks.firstVideo(resource("opaque_vp9_head.bin")))
        assertEquals("vp9", video.codec)
        assertFalse(video.hasAlpha)
    }

    // Minimal EBML writer for structures the fixtures do not cover
    private fun el(id: Long, payload: ByteArray, unknownSize: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = generateSequence(id) { it shr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toByte() }
            .toList().reversed().toByteArray()
        out.write(idBytes)
        if (unknownSize) {
            out.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1))
        } else {
            // 4-byte size: 0x10 marker
            val n = payload.size
            out.write(byteArrayOf(0x10, (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()))
        }
        out.write(payload)
        return out.toByteArray()
    }

    private fun uint(id: Long, v: Int) = el(id, byteArrayOf(v.toByte()))
    private fun str(id: Long, v: String) = el(id, v.toByteArray(Charsets.US_ASCII))
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private val ebmlHeader = el(0x1A45DFA3, str(0x4282, "webm"))

    private fun track(type: Int, codec: String, video: ByteArray? = null) =
        el(0xAE, cat(uint(0xD7, 1), uint(0x83, type), str(0x86, codec), video ?: ByteArray(0)))

    @Test
    fun `a streamed segment of unknown size with the audio track first is still read`() {
        // MediaRecorder-style output: live segment, audio track listed before the video one
        val tracks = el(
            0x1654AE6B,
            cat(track(2, "A_OPUS"), track(1, "V_VP8", el(0xE0, cat(uint(0xB0, 64), uint(0xBA, 64), el(0x53C0, byteArrayOf(1)))))),
        )
        val segment = el(0x18538067, cat(el(0x1549A966, uint(0x2AD7B1, 1)), tracks), unknownSize = true)
        val video = assertNotNull(MatroskaTracks.firstVideo(cat(ebmlHeader, segment)))
        assertEquals("vp8", video.codec)
        assertTrue(video.hasAlpha)
    }

    @Test
    fun `alpha mode zero is not alpha`() {
        val tracks = el(0x1654AE6B, track(1, "V_VP9", el(0xE0, el(0x53C0, byteArrayOf(0)))))
        val video = assertNotNull(MatroskaTracks.firstVideo(cat(ebmlHeader, el(0x18538067, tracks))))
        assertFalse(video.hasAlpha)
    }

    @Test
    fun `tracks after the first cluster or missing bytes give no answer`() {
        val cluster = el(0x1F43B675, ByteArray(16))
        val tracks = el(0x1654AE6B, track(1, "V_VP9", el(0xE0, el(0x53C0, byteArrayOf(1)))))
        assertNull(MatroskaTracks.firstVideo(cat(ebmlHeader, el(0x18538067, cat(cluster, tracks)))))
        assertNull(MatroskaTracks.firstVideo(ByteArray(0)))
        assertNull(MatroskaTracks.firstVideo(ByteArray(64) { 0x47 }))
        // Header cut off before the tracks
        assertNull(MatroskaTracks.firstVideo(resource("alpha_vp9_head.bin").copyOf(60)))
    }
}
