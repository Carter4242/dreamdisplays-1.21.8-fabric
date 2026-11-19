package com.dreamdisplays.platform.client.displays

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareChecksTest {
    @Test
    fun `a request starts one round, one while it runs makes exactly one more`() {
        val spawned = ArrayList<Runnable>()
        var rounds = 0
        lateinit var checks: ShareChecks
        checks = ShareChecks(work = {
            rounds++
            // Requests during the first round (a screen plugged in meanwhile): one more round, not three
            if (rounds == 1) repeat(3) { checks.request() }
        }, spawn = { spawned += it })
        assertFalse(checks.running)
        checks.request()
        assertTrue(checks.running)
        checks.request()
        assertEquals(1, spawned.size, "a running job is not started twice")
        spawned.single().run()
        assertEquals(2, rounds)
        assertFalse(checks.running)
        // Done: the next request starts a new round
        checks.request()
        assertEquals(2, spawned.size)
        spawned.last().run()
        assertEquals(3, rounds)
    }

    @Test
    fun `a round that throws still ends the job`() {
        val spawned = ArrayList<Runnable>()
        val checks = ShareChecks(work = { error("FFmpeg blocked") }, spawn = { spawned += it })
        checks.request()
        spawned.single().run()
        assertFalse(checks.running)
    }

    @Test
    fun `a thread that cannot start leaves the job idle`() {
        val checks = ShareChecks(work = {}, spawn = { throw OutOfMemoryError("unable to create native thread") })
        checks.request()
        assertFalse(checks.running)
    }

    /**
     * Joining a server (its hello) and switching the mod back on never run the capture tests: they start from a
     * display menu (ScreenShare.warmUp), so players who never share never run them.
     */
    @Test
    fun `server hello and the master switch never start the screen share tests`() {
        val src = File("src/main/kotlin/com/dreamdisplays/platform/client")
        val probes = Regex("""ScreenShare\s*\.\s*(prewarm|warmUp|screens)\b|ScreenCapture\s*\.\s*(choose|usableEncoders|pickEncoder|chooseFps|windowCapture|prepareEncoder|probeWindowsScreens)\b""")
        for (path in listOf("managers/ClientPacketManager.kt", "MasterSwitch.kt")) {
            val text = File(src, path).readText()
            assertTrue(text.isNotEmpty(), path)
            assertEquals(null, probes.find(text)?.value, path)
        }
        // The tests start from the display menu
        assertTrue("ScreenShare.warmUp()" in File(src, "ui/DisplayMenu.kt").readText())
        assertFalse(Regex("""fun\s+prewarm\b""").containsMatchIn(File(src, "displays/ScreenShare.kt").readText()))
    }
}
