package com.dreamdisplays.platform.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocuserTest {
    @Test
    fun displaysGoQuietWhenTheGameLosesFocus() {
        assertTrue(Focuser.shouldFocusMute(gameWindowFocused = false, inPopoutWindow = false))
        assertFalse(Focuser.shouldFocusMute(gameWindowFocused = true, inPopoutWindow = false))
    }

    @Test
    fun displayInItsOwnWindowKeepsPlayingWhenThatWindowHasFocus() {
        assertFalse(Focuser.shouldFocusMute(gameWindowFocused = false, inPopoutWindow = true))
    }
}
