package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.media.source.youtube.YouTubePlaylists
import com.dreamdisplays.platform.client.ui.widgets.PlaylistCards.Kind.PLAYLIST
import com.dreamdisplays.platform.client.ui.widgets.PlaylistCards.Kind.VIDEO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistCardsTest {
    private val list = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"
    private val video = "dQw4w9WgXcQ"

    @Test
    fun `a playlist page link shows only a playlist card`() {
        val plan = PlaylistCards.planFor("https://www.youtube.com/playlist?list=$list")
        assertEquals(listOf(PLAYLIST), plan?.kinds)
        assertEquals(YouTubePlaylists.Link(list, null), plan?.link)
        assertEquals(listOf(PLAYLIST), PlaylistCards.planFor("music.youtube.com/playlist?list=$list")?.kinds)
    }

    @Test
    fun `a video link inside a playlist shows the video's card, then the playlist's`() {
        for (q in listOf(
            "https://www.youtube.com/watch?v=$video&list=$list&index=3",
            "youtu.be/$video?list=$list",
            "  https://m.youtube.com/watch?list=$list&v=$video  ",
        )) {
            val plan = PlaylistCards.planFor(q)
            assertEquals(listOf(VIDEO, PLAYLIST), plan?.kinds, q)
            assertEquals(YouTubePlaylists.Link(list, video), plan?.link, q)
        }
    }

    @Test
    fun `everything else is searched or resolved as before`() {
        assertNull(PlaylistCards.planFor("https://www.youtube.com/watch?v=$video"))
        assertNull(PlaylistCards.planFor("https://example.com/playlist?list=$list"))
        assertNull(PlaylistCards.planFor("https://cdn.example.com/video.mp4"))
        assertNull(PlaylistCards.planFor("lofi playlist"))
        assertNull(PlaylistCards.planFor(""))
    }

    @Test
    fun `a playlist card is no custom link and leads back to its playlist`() {
        for (link in listOf(YouTubePlaylists.Link(list, null), YouTubePlaylists.Link(list, video))) {
            val card = PlaylistCards.card(link, null, "Playlist") { "$it videos" }
            assertTrue(card.isPlaylist)
            assertFalse(card.isCustom)
            assertFalse(card.isYouTubeResult, "its id is no video id to derive a thumbnail from")
            assertEquals("Playlist", card.title)
            assertNull(card.uploader)
            assertEquals(link, PlaylistCards.linkOf(card))
        }
    }

    @Test
    fun `a playlist card shows the listing's name, size and a video thumbnail`() {
        val summary = YouTubePlaylists.Summary("Road trip", 42, "aaaaaaaaaaa")
        val whole = PlaylistCards.card(YouTubePlaylists.Link(list, null), summary, "Playlist") { "$it videos" }
        assertEquals("Road trip", whole.title)
        assertEquals("42 videos", whole.uploader)
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", whole.thumbnailUrlOverride)
        val fromVideo = PlaylistCards.card(YouTubePlaylists.Link(list, video), summary.copy(title = " ", videoCount = null), "Playlist") { "$it" }
        assertEquals("Playlist", fromVideo.title)
        assertNull(fromVideo.uploader)
        assertEquals("https://i.ytimg.com/vi/$video/hqdefault.jpg", fromVideo.thumbnailUrlOverride)
    }

    @Test
    fun `video cards never lead to a playlist`() {
        val card = PlaylistCards.card(YouTubePlaylists.Link(list, video), null, "Playlist") { "" }.copy(isPlaylist = false)
        assertNull(PlaylistCards.linkOf(card))
    }
}
