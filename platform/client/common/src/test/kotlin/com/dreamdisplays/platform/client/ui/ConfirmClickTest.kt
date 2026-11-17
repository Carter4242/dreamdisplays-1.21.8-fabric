package com.dreamdisplays.platform.client.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmClickTest {
    @Test
    fun firstClickOnlyArms() {
        val c = ConfirmClick(3_000L)
        assertFalse(c.click(1_000L))
        assertTrue(c.isArmed(1_001L))
    }

    @Test
    fun secondClickInsideWindowConfirmsAndDisarms() {
        val c = ConfirmClick(3_000L)
        c.click(1_000L)
        assertTrue(c.click(3_999L))
        assertFalse(c.isArmed(4_000L))
        assertFalse(c.click(4_000L))
    }

    @Test
    fun armingExpires() {
        val c = ConfirmClick(3_000L)
        c.click(1_000L)
        assertFalse(c.isArmed(4_000L))
        assertFalse(c.click(5_000L))
        assertTrue(c.isArmed(5_001L))
    }

    @Test
    fun disarmCancels() {
        val c = ConfirmClick(3_000L)
        c.click(1_000L)
        c.disarm()
        assertFalse(c.click(1_500L))
    }
}
