package com.dreamdisplays.media.source.twitch

import com.dreamdisplays.media.player.adskip.LiveRendition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TwitchBackupPlaylistsTest {
    private fun r(group: String, h: Int?, fps: Double?) =
        TwitchHls.Rendition("https://weaver/$group.m3u8", if (h == null) null else h * 16 / 9, h, fps, 1, "avc1", group)

    private val ladder = listOf(
        r("chunked", 1080, 60.0), r("720p60", 720, 60.0), r("720p30", 720, 30.0), r("480p30", 480, 30.0), r("audio_only", null, null),
    )

    @Test
    fun `the backup plays the same picture - its group, else the same height at the nearest rate`() {
        assertEquals("chunked", TwitchBackupPlaylists.match(ladder, LiveRendition("c", 1080, 60.0, "chunked"))?.videoGroup)
        assertEquals("720p30", TwitchBackupPlaylists.match(ladder, LiveRendition("c", 720, 29.97, "720p")) ?.videoGroup)
        assertEquals("720p60", TwitchBackupPlaylists.match(ladder, LiveRendition("c", 720, 59.94, null))?.videoGroup)
    }

    @Test
    fun `a ladder without that picture size has no backup`() {
        // e.g. the autoplay player variant, which stops at 360p
        val small = listOf(r("360p30", 360, 30.0), r("160p30", 160, 30.0))
        assertNull(TwitchBackupPlaylists.match(small, LiveRendition("c", 720, 60.0, "720p60")))
        assertNull(TwitchBackupPlaylists.match(small, LiveRendition("c", null, null, "720p60")))
    }
}
