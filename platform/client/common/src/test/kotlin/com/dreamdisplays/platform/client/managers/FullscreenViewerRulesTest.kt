package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.core.protocol.common.packets.FullscreenState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullscreenViewerRulesTest {
    private val display = UUID.randomUUID()
    private val state = FullscreenState(sessionId = "s1", displayId = display, active = true, forced = true)

    @Test
    fun aRepeatOfTheShownStateIsOnlyAcked() {
        assertTrue(FullscreenViewerRules.alreadyShown(state, state, fullscreenUp = true, pipUp = false))
    }

    @Test
    fun aRepeatAfterTheOverlayClosedHereShowsItAgain() {
        // An ended share, the menu or another display's fullscreen closed it without the server knowing
        assertFalse(FullscreenViewerRules.alreadyShown(state, state, fullscreenUp = false, pipUp = false))
        // A PiP of the display is not the fullscreen the server asks for
        assertFalse(FullscreenViewerRules.alreadyShown(state, state, fullscreenUp = false, pipUp = true))
    }

    @Test
    fun aMinimizedStateIsShownWhileItsPipIsUp() {
        val minimized = state.copy(minimized = true)
        assertTrue(FullscreenViewerRules.alreadyShown(minimized, minimized, fullscreenUp = false, pipUp = true))
        assertFalse(FullscreenViewerRules.alreadyShown(minimized, minimized, fullscreenUp = false, pipUp = false))
    }

    @Test
    fun aDifferentStateIsAlwaysApplied() {
        assertFalse(FullscreenViewerRules.alreadyShown(state, state.copy(volume = 0.5f), fullscreenUp = true, pipUp = false))
        assertFalse(FullscreenViewerRules.alreadyShown(null, state, fullscreenUp = true, pipUp = false))
    }
}
