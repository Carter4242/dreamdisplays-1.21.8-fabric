package com.dreamdisplays.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigLoadsTest {
    @Test
    fun `a clean file applies, at startup or on reload`() {
        assertEquals(ConfigLoads.Action.APPLY, ConfigLoads.decide(hasErrors = false, firstLoad = true))
        assertEquals(ConfigLoads.Action.APPLY, ConfigLoads.decide(hasErrors = false, firstLoad = false))
    }

    @Test
    fun `a broken file on reload keeps what runs, at startup it applies without the destructive checks`() {
        assertEquals(ConfigLoads.Action.KEEP_PREVIOUS, ConfigLoads.decide(hasErrors = true, firstLoad = false))
        assertEquals(ConfigLoads.Action.APPLY_DEGRADED, ConfigLoads.decide(hasErrors = true, firstLoad = true))
    }
}
