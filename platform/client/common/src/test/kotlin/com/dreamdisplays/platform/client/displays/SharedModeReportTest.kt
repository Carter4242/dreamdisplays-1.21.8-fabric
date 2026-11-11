package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.playback.model.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedModeReportTest {
    @Test
    fun `a display re-reports its length when it becomes shared`() {
        assertTrue(entersSharedMode(PlaybackMode.LOCAL, PlaybackMode.SYNCED))
        assertTrue(entersSharedMode(PlaybackMode.LOCAL, PlaybackMode.BROADCAST))
        assertFalse(entersSharedMode(PlaybackMode.SYNCED, PlaybackMode.BROADCAST))
        assertFalse(entersSharedMode(PlaybackMode.SYNCED, PlaybackMode.SYNCED))
        assertFalse(entersSharedMode(PlaybackMode.SYNCED, PlaybackMode.LOCAL))
    }
}
