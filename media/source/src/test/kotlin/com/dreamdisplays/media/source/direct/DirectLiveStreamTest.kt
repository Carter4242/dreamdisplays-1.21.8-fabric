package com.dreamdisplays.media.source.direct

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.youtube.YtDlpResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectLiveStreamTest {
    // TEST-NET-3: a literal public-looking address, so the host guard needs no DNS
    private val srt = "srt://203.0.113.5:8890?streamid=read:room1"

    @Test
    fun `a relay link resolves to one live unseekable stream without any probe`() {
        val resolved = DirectStreamResolver.resolve(MediaSource.from(srt))
        assertTrue(resolved.isLive)
        assertFalse(resolved.isSeekable)
        assertEquals([srt], resolved.streams.map { it.url })
        assertTrue(resolved.streams.single().type.hasVideo && resolved.streams.single().type.hasAudio)
        assertEquals("room1", resolved.metadata.title)
    }

    @Test
    fun `a relay on a private address is refused by the host guard`() {
        assertFailsWith<DreamMediaException.NotFound> {
            DirectStreamResolver.resolve(MediaSource.from("srt://127.0.0.1:8890?streamid=read:room1"))
        }
        assertFailsWith<DreamMediaException.NotFound> {
            DirectStreamResolver.resolve(MediaSource.from("rtmp://192.168.1.10/live/a"))
        }
    }

    @Test
    fun `yt-dlp never gets a relay link`() {
        assertFalse(YtDlpResolver.canResolve(MediaSource.from(srt)))
        assertTrue(YtDlpResolver.canResolve(MediaSource.from("https://example.com/page")))
        assertTrue(DirectStreamResolver.canResolve(MediaSource.from(srt)))
    }
}
