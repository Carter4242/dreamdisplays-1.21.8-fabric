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
    fun `a shared window goes silent, and says why, when its app cannot be captured`() {
        assertEquals(
            ShareAudio.WindowSound(Choice.Off, ShareAudio.WindowSilence.NO_PROCESS_LOOPBACK),
            ShareAudio.windowSound(Os.WINDOWS, processLoopback = false, app = chrome),
        )
        assertEquals(
            ShareAudio.WindowSound(Choice.Off, ShareAudio.WindowSilence.APP_NOT_FOUND),
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
    fun `the game's own window shares the game's process tree sound like any other window`() {
        val game = ShareAudio.windowApp(root = self, name = "OpenJDK Platform binary", frameHost = false)
        assertEquals(Choice.App(self, "OpenJDK Platform binary"), game)
        val sound = ShareAudio.windowSound(Os.WINDOWS, processLoopback = true, app = game)
        // No silence note: the game's sound and the voice chat it plays are captured
        assertEquals(ShareAudio.WindowSound(game!!), sound)
        assertEquals(listOf(Plan.ProcessLoopback(self, include = true)), ShareAudio.plans(sound.choice, Os.WINDOWS, self))
    }

    @Test
    fun `a launcher or java app is its own process tree, even when the game runs under it`() {
        // Prism (the game's parent), a Java launcher, another Minecraft: the picked window's program tree is captured
        for (root in listOf(50L, 300L)) {
            val app = ShareAudio.windowApp(root, "Zulu Platform x64 Architecture", frameHost = false)
            assertEquals(Choice.App(root, "Zulu Platform x64 Architecture"), app)
            assertEquals(listOf(Plan.ProcessLoopback(root, include = true)), ShareAudio.plans(app!!, Os.WINDOWS, self))
        }
    }

    @Test
    fun `a store app whose app was not found goes silent and says so`() {
        val app = ShareAudio.windowApp(root = 77, name = "Media Player", frameHost = true)
        assertEquals(null, app)
        assertEquals(
            ShareAudio.WindowSound(Choice.Off, ShareAudio.WindowSilence.APP_NOT_FOUND),
            ShareAudio.windowSound(Os.WINDOWS, processLoopback = true, app = app),
        )
    }

    @Test
    fun `everything except minecraft still leaves the game's tree out, the game's window takes it in`() {
        assertEquals(listOf(Plan.ProcessLoopback(self, include = false)), ShareAudio.plans(Choice.ExceptGame, Os.WINDOWS, self))
        assertEquals(
            listOf(Plan.ProcessLoopback(self, include = true)),
            ShareAudio.plans(Choice.App(self, "Minecraft"), Os.WINDOWS, self),
        )
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
        assertTrue(ShareWindows.offer(facts()))
        assertTrue(ShareWindows.offer(facts(minimized = true, w = 160, h = 28)))
        // A tool window that asks for a taskbar button still counts
        assertTrue(ShareWindows.offer(facts(exStyle = 0x40080)))
    }

    @Test
    fun `the game's own window is offered like any other`() {
        // GLFW's window: a normal app window, whatever the game's pid
        val game = facts(title = "Minecraft 1.21.8 - Multiplayer (3rd-party Server)", exStyle = 0x40100, pid = 4242, w = 1920, h = 1080)
        assertTrue(ShareWindows.offer(game))
        assertTrue(ShareWindows.offer(game.copy(minimized = true, width = 160, height = 28)))
    }

    @Test
    fun `tool, cloaked, owned, untitled and tiny windows are not`() {
        assertTrue(!ShareWindows.offer(facts(exStyle = 0x80)))
        assertTrue(!ShareWindows.offer(facts(exStyle = 0x080800A0)))
        assertTrue(!ShareWindows.offer(facts(cloaked = true)))
        assertTrue(!ShareWindows.offer(facts(owned = true)))
        assertTrue(!ShareWindows.offer(facts(title = "  ")))
        assertTrue(!ShareWindows.offer(facts(w = 30, h = 600)))
        assertTrue(!ShareWindows.offer(facts(visible = false)))
        assertTrue(!ShareWindows.offer(facts(title = "Program Manager")))
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
