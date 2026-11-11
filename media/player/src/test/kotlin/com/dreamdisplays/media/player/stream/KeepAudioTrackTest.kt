package com.dreamdisplays.media.player.stream

import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class KeepAudioTrackTest {
    private fun audio(url: String, lang: String?, bitrate: Int = 128_000) =
        MediaStream(url, MediaStreamType.AUDIO, "opus", null, null, null, bitrate, null, lang)

    private val video = MediaStream("v", MediaStreamType.VIDEO, "vp9", 1920, 1080, 30.0, 4_000_000, null, null)

    private fun fresh(current: MediaStream, vararg audio: MediaStream) =
        ActiveStreams(listOf(video), audio.toList(), video, current)

    @Test
    fun `a re-resolve keeps the dub the viewer picked, at its best bitrate`() {
        val en = audio("en-1", "en")
        val frLow = audio("fr-low", "fr", 64_000)
        val frHigh = audio("fr-high", "fr", 160_000)
        val streams = MediaStreamSelector.keepAudioTrack(fresh(en, en, frLow, frHigh), "fr")
        assertEquals("fr-high", streams.currentAudio.url)
    }

    @Test
    fun `nothing changes when the pick is already current, missing, or there was none`() {
        val en = audio("en-1", "en")
        val set = fresh(en, en, audio("fr", "fr"))
        assertSame(set, MediaStreamSelector.keepAudioTrack(set, "en"))
        assertSame(set, MediaStreamSelector.keepAudioTrack(set, "de"))
        assertSame(set, MediaStreamSelector.keepAudioTrack(set, null))
    }
}
