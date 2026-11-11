package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Extra query parameters on a YouTube link (`&t=`, `&list=`, or anything a server appends) must not
 * change what is resolved, nor which cache entry a retry invalidates.
 */
class YouTubeUrlNormalizationTest {
    private val canonical = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun `extra parameters resolve to the same video`() {
        listOf(
            canonical,
            "$canonical&ddsync=1",
            "https://www.youtube.com/watch?ddsync=1&v=dQw4w9WgXcQ&t=42s",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2&pp=abc",
            "https://youtu.be/dQw4w9WgXcQ?si=xyz&t=10",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share",
            "https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1",
        ).forEach { url ->
            assertEquals(MediaSource.YouTube("dQw4w9WgXcQ"), MediaSource.from(url), url)
            assertEquals(canonical, MediaSource.from(url).toResolvableUrl(), url)
        }
    }

    @Test
    fun `the format cache is invalidated under the key the resolvers used`() {
        // YtDlp caches under toResolvableUrl(); the player invalidates with the link as it holds it
        assertEquals(canonical, YtDlp.canonicalUrl("$canonical&ddsync=1"))
        assertEquals(canonical, YtDlp.canonicalUrl("https://youtu.be/dQw4w9WgXcQ?t=5"))
    }
}
