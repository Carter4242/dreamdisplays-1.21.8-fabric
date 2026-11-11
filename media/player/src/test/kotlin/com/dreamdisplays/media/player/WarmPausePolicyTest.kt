package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarmPausePolicyTest {
    @Test
    fun `an audio track picked while paused warm reopens on resume instead of playing the old track`() {
        assertTrue(WarmPausePolicy.audioTrackChangeReopens(pausedWarm = true))
    }

    @Test
    fun `an audio track picked while playing switches in place`() {
        assertFalse(WarmPausePolicy.audioTrackChangeReopens(pausedWarm = false))
    }
}
