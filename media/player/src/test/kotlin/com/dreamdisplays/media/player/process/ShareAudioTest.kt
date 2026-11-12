package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.ShareAudio.Choice
import com.dreamdisplays.media.player.process.ShareAudio.Os
import com.dreamdisplays.media.player.process.ShareAudio.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareAudioTest {
    private val self = 4242L
    private val chrome = Choice.App(100, "Google Chrome")
    private val zuluJavaw = "C:\\Users\\me\\.jdks\\zulu-21\\bin\\javaw.exe"

    @Test
    fun `except minecraft excludes the game's own process tree and never widens to the whole device`() {
        // The whole device would add the game, its voice chat and this share's own playback (an echo)
        assertEquals(
            listOf(Plan.ProcessLoopback(self, include = false)),
            ShareAudio.plans(Choice.ExceptGame, Os.WINDOWS, self),
        )
        assertEquals(listOf(Plan.DeviceLoopback), ShareAudio.plans(Choice.WholePc, Os.WINDOWS, self))
    }

    @Test
    fun `just one app never widens to the whole pc`() {
        assertEquals(listOf(Plan.ProcessLoopback(100, include = true)), ShareAudio.plans(chrome, Os.WINDOWS, self))
    }

    @Test
    fun `no audio, macos and linux`() {
        for (os in Os.entries) assertTrue(ShareAudio.plans(Choice.Off, os, self).isEmpty())
        assertTrue(ShareAudio.plans(Choice.ExceptGame, Os.MAC, self).isEmpty())
        assertEquals(listOf(Plan.Pulse("@DEFAULT_MONITOR@")), ShareAudio.plans(Choice.ExceptGame, Os.LINUX, self))
        assertEquals(listOf(Plan.Pulse()), ShareAudio.plans(Choice.WholePc, Os.LINUX, self))
    }

    @Test
    fun `a screen offers exactly except minecraft, whole pc and no audio, except minecraft preselected`() {
        assertEquals(
            listOf(Choice.ExceptGame, Choice.WholePc, Choice.Off),
            ShareAudio.options(Os.WINDOWS, processLoopback = true),
        )
        assertEquals(Choice.ExceptGame, ShareAudio.defaultChoice(Os.WINDOWS, processLoopback = true))
        // No per-app choices for screens
        assertTrue(ShareAudio.options(Os.WINDOWS, true).none { it is Choice.App })
    }

    @Test
    fun `without process loopback only the whole pc or nothing is offered, nothing preselected`() {
        assertEquals(listOf(Choice.WholePc, Choice.Off), ShareAudio.options(Os.WINDOWS, processLoopback = false))
        // Whole PC carries the game and voice chat (an echo at the display): offered, never preselected
        assertEquals(Choice.Off, ShareAudio.defaultChoice(Os.WINDOWS, false))
        assertEquals(listOf(Choice.WholePc, Choice.Off), ShareAudio.options(Os.LINUX, false))
        assertEquals(listOf(Choice.WholePc, Choice.Off), ShareAudio.options(Os.LINUX, true))
        assertEquals(Choice.Off, ShareAudio.defaultChoice(Os.LINUX, false))
        assertEquals(Choice.Off, ShareAudio.defaultChoice(Os.LINUX, true))
        // macOS: no sound step
        assertTrue(ShareAudio.options(Os.MAC, false).isEmpty())
        assertTrue(ShareAudio.options(Os.MAC, true).isEmpty())
        assertEquals(Choice.Off, ShareAudio.defaultChoice(Os.MAC, false))
    }

    @Test
    fun `every default is one of the options shown`() {
        for (os in Os.entries) for (loopback in listOf(true, false)) {
            val options = ShareAudio.options(os, loopback)
            if (options.isNotEmpty()) assertTrue(ShareAudio.defaultChoice(os, loopback) in options, "$os $loopback")
        }
    }

    @Test
    fun `a shared window takes its own app's sound, without a step`() {
        assertEquals(ShareAudio.WindowSound(chrome), ShareAudio.windowSound(Os.WINDOWS, processLoopback = true, app = chrome))
        assertEquals(
            listOf(Plan.ProcessLoopback(100, include = true)),
            ShareAudio.plans(ShareAudio.windowSound(Os.WINDOWS, true, chrome).choice, Os.WINDOWS, self),
        )
    }

    @Test
    fun `a shared window goes silent, and says why, when its app cannot be captured on its own`() {
        assertEquals(
            ShareAudio.WindowSound(Choice.Off, ShareAudio.WindowSilence.NO_PROCESS_LOOPBACK),
            ShareAudio.windowSound(Os.WINDOWS, processLoopback = false, app = chrome),
        )
        assertEquals(
            ShareAudio.WindowSound(Choice.Off, ShareAudio.WindowSilence.NOT_CAPTURABLE),
            ShareAudio.windowSound(Os.WINDOWS, processLoopback = true, app = null),
        )
        // Never widened to everything but the game or the whole PC
        for (loopback in listOf(true, false)) for (app in listOf(chrome, null)) {
            val choice = ShareAudio.windowSound(Os.WINDOWS, loopback, app).choice
            assertTrue(choice == Choice.Off || choice is Choice.App)
        }
        assertEquals(ShareAudio.WindowSound(Choice.Off), ShareAudio.windowSound(Os.LINUX, true, chrome))
        assertEquals(ShareAudio.WindowSound(Choice.Off), ShareAudio.windowSound(Os.MAC, true, chrome))
    }

    @Test
    fun `an app the game runs under is never just this app`() {
        // Prism -> java (the game): Prism's process tree includes the game, its sound and its voice chat
        assertTrue(ShareAudio.includesGame(root = 50, selfPid = self, selfAncestors = listOf(50, 4)))
        assertTrue(ShareAudio.includesGame(root = self, selfPid = self, selfAncestors = listOf(50)))
        assertFalse(ShareAudio.includesGame(root = 100, selfPid = self, selfAncestors = listOf(50, 4)))
        assertFalse(
            ShareAudio.includesGame(100, self, listOf(50), "C:\\Program Files\\Google\\chrome.exe", zuluJavaw),
        )
    }

    @Test
    fun `a jvm is never just this app, whatever its pid`() {
        // "Zulu Platform x64 Architecture": another Minecraft, a Java launcher, or the game under a pid not seen as it
        assertTrue(ShareAudio.includesGame(300, self, listOf(50), zuluJavaw, zuluJavaw))
        assertTrue(ShareAudio.includesGame(300, self, listOf(50), "C:\\Program Files\\Java\\bin\\java.exe", null))
        assertTrue(ShareAudio.includesGame(300, self, listOf(50), "/usr/lib/jvm/bin/java", null))
        // The game's own executable, whatever it is called
        assertTrue(ShareAudio.includesGame(300, self, listOf(50), "C:\\Games\\mc\\Runtime.EXE", "c:\\games\\mc\\runtime.exe"))
        assertTrue(ShareAudio.isJvm("JAVAW.EXE"))
        assertFalse(ShareAudio.isJvm("C:\\Program Files\\JavaScript\\node.exe"))
        assertFalse(ShareAudio.isJvm("javascript.exe"))
    }

    @Test
    fun `the game itself is never captured as one app`() {
        // Even if one were asked for, the game's own pid as "just this app" is no sound
        assertTrue(ShareAudio.plans(Choice.App(self, "Minecraft"), Os.WINDOWS, self).isEmpty())
    }

    @Test
    fun `pcm layouts and their ten millisecond packets`() {
        assertEquals(4, ShareAudio.Format(48_000, 2, float = false).bytesPerFrame)
        assertEquals(1920, ShareAudio.Format(48_000, 2, float = false).tenMillisBytes)
        assertEquals(24, ShareAudio.Format(44_100, 6, float = true).bytesPerFrame)
        assertEquals(10_584, ShareAudio.Format(44_100, 6, float = true).tenMillisBytes)
    }

    @Test
    fun `the wav header describes the pcm with unknown lengths`() {
        val h = java.nio.ByteBuffer.wrap(ShareAudio.wavHeader(ShareAudio.Format(48_000, 2, float = false)))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals("RIFF", String(h.array(), 0, 4, Charsets.US_ASCII))
        assertEquals(-1, h.getInt(4))
        assertEquals("WAVEfmt ", String(h.array(), 8, 8, Charsets.US_ASCII))
        assertEquals(16, h.getInt(16))
        assertEquals(1, h.getShort(20).toInt())
        assertEquals(2, h.getShort(22).toInt())
        assertEquals(48_000, h.getInt(24))
        assertEquals(192_000, h.getInt(28))
        assertEquals(4, h.getShort(32).toInt())
        assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(h.array(), 36, 4, Charsets.US_ASCII))
        assertEquals(-1, h.getInt(40))
        val f = java.nio.ByteBuffer.wrap(ShareAudio.wavHeader(ShareAudio.Format(44_100, 6, float = true)))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(3, f.getShort(20).toInt())
        assertEquals(6, f.getShort(22).toInt())
        assertEquals(32, f.getShort(34).toInt())
    }
}

class AudioTimelineTest {
    private val ms = 1_000_000L
    private val start = 10_000 * ms

    @Test
    fun `packets on time are written back to back`() {
        val t = AudioTimeline(48_000, start)
        assertEquals(AudioTimeline.Placement(0, 0), t.place(start, 480))
        assertEquals(AudioTimeline.Placement(0, 0), t.place(start + 10 * ms, 480))
        // A few ms of jitter is not corrected
        assertEquals(AudioTimeline.Placement(0, 0), t.place(start + 23 * ms, 480))
        assertEquals(1440L, t.written)
    }

    @Test
    fun `a gap in loopback becomes silence at the packet's true place`() {
        val t = AudioTimeline(48_000, start)
        t.place(start, 480)
        // Nothing played for 490 ms, then sound again at 500 ms
        assertEquals(AudioTimeline.Placement(23_520, 0), t.place(start + 500 * ms, 480))
        assertEquals(24_480L, t.written)
    }

    @Test
    fun `captured before the clock base, or already covered by silence, is skipped`() {
        val t = AudioTimeline(48_000, start)
        // Starts 20 ms before the base: its first 960 frames are dropped
        assertEquals(AudioTimeline.Placement(0, 960), t.place(start - 20 * ms, 1440))
        assertEquals(480L, t.written)
        // Silence written up to 100 ms (with the 60 ms grace: up to 40 ms of stream)
        assertEquals(1440L, t.silenceDue(start + 100 * ms))
        assertEquals(1920L, t.written)
        // A packet from 20 ms arrives late: its first 960 frames are already silence
        assertEquals(AudioTimeline.Placement(0, 960), t.place(start + 20 * ms, 1440))
        assertEquals(2400L, t.written)
    }

    @Test
    fun `silence keeps the stream flowing while nothing plays, never ahead of real time minus the grace`() {
        val t = AudioTimeline(48_000, start)
        assertEquals(0L, t.silenceDue(start + 30 * ms))
        assertEquals(0L, t.silenceDue(start + 60 * ms))
        assertEquals(480L, t.silenceDue(start + 70 * ms))
        assertEquals(0L, t.silenceDue(start + 70 * ms))
        // Two minutes on, exactly two minutes minus the grace of frames exist: no drift from rounding
        t.silenceDue(start + 120_000 * ms)
        assertEquals(48_000L * 120 - 48 * 60, t.written)
    }
}

class ShareWindowsTest {
    private fun facts(
        title: String = "Video - Google Chrome", exStyle: Int = 0x100, cloaked: Boolean = false, owned: Boolean = false,
        pid: Long = 10, w: Int = 1280, h: Int = 720, minimized: Boolean = false, visible: Boolean = true,
    ) = ShareWindows.Facts(visible, title, exStyle, cloaked, owned, pid, w, h, minimized)

    @Test
    fun `ordinary app windows are offered`() {
        assertTrue(ShareWindows.offer(facts(), selfPid = 1))
        assertTrue(ShareWindows.offer(facts(minimized = true, w = 160, h = 28), selfPid = 1))
        // A tool window that asks for a taskbar button still counts
        assertTrue(ShareWindows.offer(facts(exStyle = 0x40080), selfPid = 1))
    }

    @Test
    fun `tool, cloaked, owned, untitled, tiny and own windows are not`() {
        assertTrue(!ShareWindows.offer(facts(exStyle = 0x80), 1))
        assertTrue(!ShareWindows.offer(facts(exStyle = 0x080800A0), 1))
        assertTrue(!ShareWindows.offer(facts(cloaked = true), 1))
        assertTrue(!ShareWindows.offer(facts(owned = true), 1))
        assertTrue(!ShareWindows.offer(facts(title = "  "), 1))
        assertTrue(!ShareWindows.offer(facts(w = 30, h = 600), 1))
        assertTrue(!ShareWindows.offer(facts(pid = 1), 1))
        assertTrue(!ShareWindows.offer(facts(visible = false), 1))
        assertTrue(!ShareWindows.offer(facts(title = "Program Manager"), 1))
    }

    @Test
    fun `app names prefer the file description`() {
        assertEquals("Google Chrome", ShareWindows.appName(" Google Chrome ", "C:\\Program Files\\Google\\chrome.exe"))
        assertEquals("chrome", ShareWindows.appName(null, "C:\\Program Files\\Google\\chrome.exe"))
        assertEquals("App", ShareWindows.appName("", null))
    }

    @Test
    fun `a store app's frame host is recognised, so its window gets no silent this-app sound`() {
        assertTrue(ShareWindows.isFrameHostExe("C:\\Windows\\System32\\ApplicationFrameHost.exe"))
        assertTrue(ShareWindows.isFrameHostExe("c:\\windows\\system32\\applicationframehost.EXE"))
        assertFalse(ShareWindows.isFrameHostExe("C:\\Program Files\\Google\\chrome.exe"))
    }
}
