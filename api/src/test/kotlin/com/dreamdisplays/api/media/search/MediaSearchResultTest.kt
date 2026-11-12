package com.dreamdisplays.api.media.search

import com.dreamdisplays.api.media.source.CustomMediaUrls
import com.dreamdisplays.api.media.source.MediaPlatform
import com.dreamdisplays.api.media.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaSearchResultTest {
    @Test
    fun `a pasted non-YouTube link has its own watch URL and no thumbnail`() {
        // The card the search box builds for a pasted link (SuggestionsController.customResult)
        val result = MediaSearchResult(
            id = INSTAGRAM, title = CustomMediaUrls.displayName(INSTAGRAM), uploader = CustomMediaUrls.hostOf(INSTAGRAM),
            durationSec = null, viewCount = null, watchUrlOverride = INSTAGRAM, isCustom = true,
        )
        assertFalse(result.isYouTubeResult)
        assertEquals(INSTAGRAM, result.getWatchUrl())
        assertNull(result.getThumbnailUrl())
        assertTrue(result.title.isNotBlank())
    }

    @Test
    fun `a custom result without overrides falls back to its id and never throws`() {
        for (id in [INSTAGRAM, "https://cdn.example.com/v.mp4", "not a link at all", "", "  "]) {
            val result = MediaSearchResult(id, "t", null, null, null, isCustom = true)
            assertEquals(id, result.getWatchUrl(), id)
            assertNull(result.getThumbnailUrl(), id)
        }
    }

    @Test
    fun `a Twitch result uses its overrides and derives nothing from its key`() {
        val withMeta = MediaSearchResult(
            id = "twitch:channel:someone", title = "t", uploader = null, durationSec = null, viewCount = null,
            watchUrlOverride = "https://www.twitch.tv/someone", thumbnailUrlOverride = "https://static-cdn.jtvnw.net/x.jpg",
            isTwitch = true, platform = MediaPlatform.TWITCH,
        )
        assertEquals("https://www.twitch.tv/someone", withMeta.getWatchUrl())
        assertEquals("https://static-cdn.jtvnw.net/x.jpg", withMeta.getThumbnailUrl())

        // Metadata lookup failed: no thumbnail, and an 11-character key still is not a YouTube id
        val noMeta = withMeta.copy(id = "abcdefghijk", thumbnailUrlOverride = null)
        assertFalse(noMeta.isYouTubeResult)
        assertNull(noMeta.getThumbnailUrl())
        assertEquals("https://www.twitch.tv/someone", noMeta.getWatchUrl())
    }

    @Test
    fun `a platform card without metadata has no thumbnail`() {
        val vimeo = MediaSearchResult(
            id = "https://vimeo.com/76979871", title = "t", uploader = null, durationSec = null, viewCount = null,
            watchUrlOverride = "https://vimeo.com/76979871", platform = MediaPlatform.VIMEO,
        )
        assertNull(vimeo.getThumbnailUrl())
        assertEquals("https://vimeo.com/76979871", vimeo.getWatchUrl())
    }

    @Test
    fun `a YouTube result derives its watch and thumbnail URLs`() {
        val result = MediaSearchResult(VIDEO_ID, "t", null, null, null)
        assertTrue(result.isYouTubeResult)
        assertEquals("https://www.youtube.com/watch?v=$VIDEO_ID", result.getWatchUrl())
        assertEquals("https://i.ytimg.com/vi/$VIDEO_ID/hqdefault.jpg", result.getThumbnailUrl())
    }

    @Test
    fun `a YouTube-platform result with a bad id never throws`() {
        for (id in [INSTAGRAM, "short", "", "dQw4w9WgXcQ?x", "dQw4w9WgXc!"]) {
            val result = MediaSearchResult(id, "t", null, null, null)
            assertFalse(result.isYouTubeResult, id)
            assertNull(result.getThumbnailUrl(), id)
            assertEquals(id, result.getWatchUrl(), id)
        }
    }

    @Test
    fun `overrides win over derived URLs`() {
        val result = MediaSearchResult(
            VIDEO_ID, "t", null, null, null,
            watchUrlOverride = "https://example.com/w", thumbnailUrlOverride = "https://example.com/t.jpg",
        )
        assertEquals("https://example.com/w", result.getWatchUrl())
        assertEquals("https://example.com/t.jpg", result.getThumbnailUrl())
    }

    @Test
    fun `a YouTube source only resolves for a real id`() {
        assertEquals("https://www.youtube.com/watch?v=$VIDEO_ID", MediaSource.YouTube(VIDEO_ID).toResolvableUrl())
        assertNull(MediaSource.YouTube(INSTAGRAM).toResolvableUrl())
        assertNull(MediaSource.YouTube("garbage").toResolvableUrl())
        assertEquals(INSTAGRAM, MediaSource.from(INSTAGRAM).toResolvableUrl())
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val INSTAGRAM = "https://www.instagram.com/reel/Dfku6yHuW2R"
    }
}
