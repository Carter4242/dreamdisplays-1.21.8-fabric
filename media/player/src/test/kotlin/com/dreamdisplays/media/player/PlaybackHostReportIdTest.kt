package com.dreamdisplays.media.player

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.player.PlaybackHost
import com.dreamdisplays.api.playback.model.PlaybackMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHostReportIdTest {
    private open class Host(override val uuid: UUID) : PlaybackHost {
        override val quality: VideoQuality = VideoQuality.Auto
        override val textureWidth = 0
        override val textureHeight = 0
        override val isPaused = false
        override val effectiveMode = PlaybackMode.LOCAL
        override val shouldLoopOnEnd = true
        override var videoContentAspect = 0.0
        override var mediaError: DreamMediaException? = null
        override fun afterSeek() {}
        override fun beginQualityHandoff() {}
        override fun cancelQualityHandoff() {}
        override fun reloadTexture() {}
        override fun onPlaybackEnded(positionNanos: Long) {}
    }

    @Test
    fun `a display's own player reports under its id`() {
        val id = UUID.randomUUID()
        assertEquals(id, Host(id).reportId)
    }

    @Test
    fun `a background player keeps its own buffer id but reports under its display`() {
        val display = UUID.randomUUID()
        val host = object : Host(UUID.randomUUID()) {
            override val reportId: UUID get() = display
        }
        assertEquals(display, host.reportId)
    }
}
