package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.ShareAudio.Choice
import com.dreamdisplays.media.player.process.ShareAudio.Os
import com.dreamdisplays.media.player.process.ShareAudio.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareAudioTest {
    private val self = 4242L
    private val chrome = Choice.App(100, "Google Chrome")
    private val spotify = Choice.App(200, "Spotify")

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
    fun `the picker offers except minecraft first, then whole pc, the playing apps and no audio`() {
        assertEquals(
            listOf(Choice.ExceptGame, Choice.WholePc, chrome, spotify, Choice.Off),
            ShareAudio.options(Os.WINDOWS, processLoopback = true, apps = listOf(chrome, spotify)),
        )
        assertEquals(Choice.ExceptGame, ShareAudio.defaultChoice(Os.WINDOWS, processLoopback = true))
    }

    @Test
    fun `a picked window offers and defaults to its own app, listed once`() {
        val options = ShareAudio.options(Os.WINDOWS, true, listOf(chrome, spotify), window = chrome)
        assertEquals(listOf(chrome, Choice.ExceptGame, Choice.WholePc, spotify, Choice.Off), options)
        assertEquals(chrome, ShareAudio.defaultChoice(Os.WINDOWS, true, window = chrome))
    }

    @Test
    fun `without process loopback only the whole pc or nothing is offered`() {
        assertEquals(
            listOf(Choice.WholePc, Choice.Off),
            ShareAudio.options(Os.WINDOWS, processLoopback = false, apps = listOf(chrome), window = chrome),
        )
        assertEquals(Choice.WholePc, ShareAudio.defaultChoice(Os.WINDOWS, false, window = chrome))
        assertEquals(listOf(Choice.WholePc, Choice.Off), ShareAudio.options(Os.LINUX, false, emptyList()))
        assertTrue(ShareAudio.options(Os.MAC, false, emptyList()).isEmpty())
        assertEquals(Choice.Off, ShareAudio.defaultChoice(Os.MAC, false))
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
}
