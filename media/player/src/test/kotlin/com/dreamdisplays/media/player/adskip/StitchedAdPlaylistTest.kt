package com.dreamdisplays.media.player.adskip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StitchedAdPlaylistTest {
    private val base = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/x.m3u8"

    private fun resource(name: String) = javaClass.getResource("/adskip/$name")!!.readText()

    @Test
    fun `a real pre-roll is read as ad segments, its date range and the stream's return`() {
        val p = StitchedAdPlaylist.parse(resource("twitch-preroll.m3u8"), base)
        assertEquals(17, p.segments.size)
        assertEquals(16, p.segments.count { it.ad })
        assertTrue(p.segments.dropLast(1).all { it.ad && it.title!!.startsWith("Amazon|") && it.liveSeq == null })
        val back = p.segments.last()
        assertFalse(back.ad)
        assertEquals(9408L, back.liveSeq)
        assertTrue(back.discontinuity)
        // The stitched ad's range; the quartile beacons are ads too but say nothing about its length
        val ranges = p.adRanges.filter { it.durationSec != null }
        assertEquals(1, ranges.size)
        assertEquals(30.235, ranges[0].durationSec)
        assertEquals(6, p.adRanges.size)
        assertFalse(p.adAtEdge)
        assertTrue(p.hasAdMarkers)
        assertEquals(0.0, p.adSecondsAfterEdge())
    }

    @Test
    fun `a real clean playlist has no ads and every segment numbered`() {
        val p = StitchedAdPlaylist.parse(resource("twitch-clean.m3u8"), base)
        assertEquals(15, p.segments.size)
        assertFalse(p.hasAdMarkers)
        assertEquals((9393L..9407L).toList(), p.segments.map { it.liveSeq })
        assertEquals(9393L, p.mediaSequence)
    }

    @Test
    fun `an ad is told by its date range even with a live title, and by its title without a range`() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-DATERANGE:ID="stitched-ad-1-2",CLASS="twitch-stitched-ad",START-DATE="2026-01-01T00:00:02.000Z",DURATION=2.000
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z
            #EXTINF:2.000,live
            a.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:02.000Z
            #EXTINF:2.000,live
            b.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:04.000Z
            #EXTINF:2.000,Slate
            c.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:06.000Z
            #EXTINF:2.000,
            d.ts
        """.trimIndent()
        val p = StitchedAdPlaylist.parse(body, "https://cdn.example/live/x.m3u8")
        assertEquals(listOf(false, true, true, false), p.segments.map { it.ad })
        assertEquals("https://cdn.example/live/a.ts", p.segments[0].uri)
        assertEquals(13L, p.segments[3].seq)
        assertNull(p.segments[0].liveSeq)
    }

    @Test
    fun `attribute lists keep quoted commas`() {
        val a = StitchedAdPlaylist.attributes("""ID="x,y",CLASS="twitch-stitched-ad",DURATION=15.5,X-TV-TWITCH-AD-URL="https://a/b?c=1,2"""")
        assertEquals("x,y", a["ID"])
        assertEquals("15.5", a["DURATION"])
        assertEquals("https://a/b?c=1,2", a["X-TV-TWITCH-AD-URL"])
    }
}
