package com.dreamdisplays.platform.client.replay

import com.dreamdisplays.core.display.policy.SyncSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ReplaySyncSettingsTest {
    @Test
    fun `outside a replay the display's own settings apply`() {
        val base = SyncSettings.DEFAULT
        assertSame(base, ReplayHooks.syncSettings(base, 4.0))
    }

    @Test
    fun `in a replay drift is seeked at about 400 ms of content at any speed`() {
        val base = SyncSettings.DEFAULT
        assertEquals(400, ReplayHooks.replaySyncSettings(base, 1.0).seekThresholdMs)
        assertEquals(200, ReplayHooks.replaySyncSettings(base, 2.0).seekThresholdMs)
        assertEquals(100, ReplayHooks.replaySyncSettings(base, 4.0).seekThresholdMs)
        assertEquals(100, ReplayHooks.replaySyncSettings(base, 10.0).seekThresholdMs) // floor
        assertEquals(1_500, ReplayHooks.replaySyncSettings(base, 0.1).seekThresholdMs) // never looser than its own
        assertEquals(2_000, ReplayHooks.replaySyncSettings(base, 1.0).seekCooldownMs)
    }

    @Test
    fun `the dead band stays inside the threshold, and a policy without corrective seeks keeps it`() {
        val tight = ReplayHooks.replaySyncSettings(SyncSettings(deadBandMs = 300), 4.0)
        assertEquals(50, tight.deadBandMs)
        assertEquals(false, ReplayHooks.replaySyncSettings(SyncSettings(correctiveSeeks = false), 1.0).correctiveSeeks)
    }
}
