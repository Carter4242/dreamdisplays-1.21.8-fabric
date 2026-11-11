package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.ui.widgets.ArmedClick
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmedClickTest {
    @Test
    fun firstClickOnlyArms() {
        val guard = ArmedClick(3_000)
        assertFalse(guard.press(1_000))
        assertTrue(guard.isArmed(1_000))
    }

    @Test
    fun secondClickInTimeConfirmsOnce() {
        val guard = ArmedClick(3_000)
        guard.press(1_000)
        assertTrue(guard.press(2_500))
        // Confirming disarms: a third click starts over
        assertFalse(guard.isArmed(2_600))
        assertFalse(guard.press(2_700))
    }

    @Test
    fun lateSecondClickArmsAgainInsteadOfConfirming() {
        val guard = ArmedClick(3_000)
        guard.press(1_000)
        assertFalse(guard.press(4_000))
        assertTrue(guard.isArmed(4_000))
    }

    @Test
    fun disarmCancels() {
        val guard = ArmedClick(3_000)
        guard.press(1_000)
        guard.disarm()
        assertFalse(guard.press(1_500))
    }
}
