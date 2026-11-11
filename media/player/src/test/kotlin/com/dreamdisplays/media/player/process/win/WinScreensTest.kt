package com.dreamdisplays.media.player.process.win

import com.dreamdisplays.media.player.process.Dxgi
import com.dreamdisplays.media.player.process.ScreenCapture
import com.dreamdisplays.util.OsInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads the screen list only (no capture): user32's view of each screen matches DXGI's, monitor ids included. */
class WinScreensTest {
    @Test
    fun `every desktop screen dxgi lists is found at its rectangle under its name and monitors`() {
        if (!OsInfo.isWindows) return
        val outputs = Dxgi.enumerate().outputs.filter { it.attachedToDesktop && it.width > 0 }
        println("screens: ${outputs.size}")
        for (o in outputs) {
            val key = WinScreens.monitorKey(o.deviceName)
            println("${o.deviceName}: ${key.length} chars")
            assertTrue(key.isEmpty() || key.split('|').all { it.contains("display#") }, key)
            val at = WinScreens.screenAt(o.left, o.top, o.width, o.height)!!
            assertEquals(o.deviceName, at.deviceName)
            assertEquals(key, at.key)
            val picked = ScreenCapture.Monitor(o.index, "", o.left, o.top, o.width, o.height, deviceName = o.deviceName, key = key)
            assertTrue(ScreenCapture.rectStillShows(picked, at, WinScreens.screenAt(o.left, o.top, o.width, o.height)))
        }
    }
}
