package com.dreamdisplays.platform.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MonitorPickTest {
    private val primary = MonitorPick.Rect(0, 0, 2560, 1440)
    private val right = MonitorPick.Rect(2560, 200, 1920, 1080)
    private val left = MonitorPick.Rect(-1920, 0, 1920, 1080)

    @Test
    fun windowOnTheSecondMonitorGoesFullscreenThere() {
        assertEquals(1, MonitorPick.indexFor(3000, 600, listOf(primary, right)))
        assertEquals(2, MonitorPick.indexFor(-500, 300, listOf(primary, right, left)))
    }

    @Test
    fun windowOnThePrimaryStaysThere() {
        assertEquals(0, MonitorPick.indexFor(1280, 720, listOf(primary, right)))
    }

    @Test
    fun centreOffEveryMonitorPicksTheNearest() {
        assertEquals(1, MonitorPick.indexFor(4600, 1400, listOf(primary, right)))
    }

    @Test
    fun noUsableMonitor() {
        assertEquals(-1, MonitorPick.indexFor(0, 0, emptyList()))
        assertEquals(-1, MonitorPick.indexFor(0, 0, listOf(MonitorPick.Rect(0, 0, 0, 0))))
    }
}
