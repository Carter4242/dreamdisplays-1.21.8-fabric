package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.model.VideoQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BroadcastOverridesTest {
    private val own720 = VideoQuality.parse("720")

    @Test
    fun broadcastValuesPlayOverTheViewersWithoutReplacingThem() {
        val o = BroadcastOverrides()
        o.apply(1.0f, "1080")
        assertEquals(1.0f, o.volumeOver(0.2f))
        assertEquals(VideoQuality.parse("1080"), o.qualityOver(own720))
        o.clear()
        // The viewer's own 20 % / 720p are what remains
        assertEquals(0.2f, o.volumeOver(0.2f))
        assertEquals(own720, o.qualityOver(own720))
    }

    @Test
    fun unsetBroadcastValuesLeaveTheViewersInPlace() {
        val o = BroadcastOverrides()
        val changed = o.apply(-1f, "")
        assertFalse(changed.volume)
        assertFalse(changed.quality)
        assertEquals(0.4f, o.volumeOver(0.4f))
        assertEquals(own720, o.qualityOver(own720))
    }

    @Test
    fun viewerChangeDuringTheBroadcastWins() {
        val o = BroadcastOverrides()
        o.apply(1.0f, "1080")
        o.onViewerVolume()
        o.onViewerQuality()
        assertEquals(0.3f, o.volumeOver(0.3f))
        assertEquals(own720, o.qualityOver(own720))
    }

    @Test
    fun reapplyingTheSameStateChangesNothing() {
        val o = BroadcastOverrides()
        assertTrue(o.apply(0.5f, "480").quality)
        val again = o.apply(0.5f, "480")
        assertFalse(again.volume)
        assertFalse(again.quality)
    }

    @Test
    fun volumeIsClamped() {
        val o = BroadcastOverrides()
        o.apply(3f, "")
        assertEquals(1f, o.volumeOver(0f))
    }

    @Test
    fun clearReportsWhatWasOverridden() {
        val o = BroadcastOverrides()
        o.apply(0.5f, "")
        val c = o.clear()
        assertTrue(c.volume)
        assertFalse(c.quality)
    }
}
