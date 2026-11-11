package com.dreamdisplays.media.player.process

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class HlsAudioFeederTest {
    private fun box(type: String, version: Int, fields: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(64).apply {
            putInt(64)
            put(type.toByteArray(Charsets.ISO_8859_1))
            put(version.toByte()).put(ByteArray(3))
            fields()
        }.array()

    @Test
    fun `the first PTS of an fMP4 segment is its decode time over the init timescale`() {
        val init = box("mdhd", 0) { putInt(0).putInt(0).putInt(48_000) }
        val segment = box("tfdt", 1) { putLong(891_553_824L) }
        assertEquals(18_574_038_000_000L, HlsAudioFeeder.mp4FirstPtsNanos(init, segment))
    }

    @Test
    fun `both box versions are read and a long stream does not overflow`() {
        val init = box("mdhd", 1) { putLong(0).putLong(0).putInt(90_000) }
        val late = box("tfdt", 1) { putLong(40L * 3_600 * 90_000) }
        assertEquals(40L * 3_600 * 1_000_000_000L, HlsAudioFeeder.mp4FirstPtsNanos(init, late))
        val early = box("tfdt", 0) { putInt(45_000) }
        assertEquals(500_000_000L, HlsAudioFeeder.mp4FirstPtsNanos(init, early))
    }

    @Test
    fun `a segment without the boxes yields no PTS`() {
        assertEquals(-1L, HlsAudioFeeder.mp4FirstPtsNanos(ByteArray(64), ByteArray(64)))
    }

    @Test
    fun `a playlist with an AES key is recognised as one the feeder cannot read`() {
        val base = "https://live.example/hls/audio.m3u8"
        val plain = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:2.0,\nseg7.ts\n"
        val aes = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k\"\n#EXTINF:2.0,\nseg7.ts\n"
        val none = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:2.0,\nseg7.ts\n"
        kotlin.test.assertFalse(HlsAudioFeeder.encryptedPlaylist(plain, base))
        kotlin.test.assertTrue(HlsAudioFeeder.encryptedPlaylist(aes, base))
        kotlin.test.assertFalse(HlsAudioFeeder.encryptedPlaylist(none, base))
    }
}
