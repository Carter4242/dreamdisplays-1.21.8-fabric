package com.dreamdisplays.media.source.youtube

import org.schabi.newpipe.extractor.stream.ContentAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubePlaylistsTest {
    private val list = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"

    @Test
    fun `playlist links are recognised with their start video`() {
        assertEquals(
            YouTubePlaylists.Link(list, null),
            YouTubePlaylists.parseLink("https://www.youtube.com/playlist?list=$list"),
        )
        assertEquals(
            YouTubePlaylists.Link(list, "dQw4w9WgXcQ"),
            YouTubePlaylists.parseLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=$list&index=3"),
        )
        assertEquals(
            YouTubePlaylists.Link(list, "dQw4w9WgXcQ"),
            YouTubePlaylists.parseLink("youtu.be/dQw4w9WgXcQ?list=$list"),
        )
        assertEquals(YouTubePlaylists.Link(list, null), YouTubePlaylists.parseLink("music.youtube.com/playlist?list=$list"))
    }

    @Test
    fun `other links are not playlists`() {
        assertNull(YouTubePlaylists.parseLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubePlaylists.parseLink("https://example.com/playlist?list=$list"))
        assertNull(YouTubePlaylists.parseLink("https://www.youtube.com/playlist?list=bad list"))
        assertNull(YouTubePlaylists.parseLink("cats playlist"))
        assertNull(YouTubePlaylists.parseLink(""))
    }

    @Test
    fun `unavailable entries are skipped`() {
        assertTrue(YouTubePlaylists.isPlayable(ContentAvailability.AVAILABLE, "A video"))
        assertTrue(YouTubePlaylists.isPlayable(null, "A video"))
        assertFalse(YouTubePlaylists.isPlayable(ContentAvailability.MEMBERSHIP, "A video"))
        assertFalse(YouTubePlaylists.isPlayable(ContentAvailability.UPCOMING, "A video"))
        assertFalse(YouTubePlaylists.isPlayable(ContentAvailability.AVAILABLE, "[Private video]"))
        assertFalse(YouTubePlaylists.isPlayable(null, "[Deleted video]"))
    }
}
