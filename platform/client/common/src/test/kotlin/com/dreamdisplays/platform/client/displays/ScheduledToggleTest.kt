package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.playback.model.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledToggleTest {
    @Test
    fun `a scheduled play or pause applies to a Local display for every viewer`() {
        assertTrue(scheduledToggleApplies(PlaybackMode.LOCAL))
    }

    @Test
    fun `a scheduled toggle never overrides a timeline`() {
        assertFalse(scheduledToggleApplies(PlaybackMode.SYNCED))
        assertFalse(scheduledToggleApplies(PlaybackMode.BROADCAST))
    }
}
