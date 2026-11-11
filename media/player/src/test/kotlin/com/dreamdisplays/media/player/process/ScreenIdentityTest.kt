package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.ScreenCapture.Encoder
import com.dreamdisplays.media.player.process.ScreenCapture.Located
import com.dreamdisplays.media.player.process.ScreenCapture.Monitor
import com.dreamdisplays.media.player.process.ScreenCapture.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A share only ever captures the screen that was picked: found by what names it, never by an index or rectangle. */
class ScreenIdentityTest {
    private val dell = "\\\\?\\display#del40f7#5&2a1b&0&uid4352#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}"
    private val lg = "\\\\?\\display#gsm5b7f#5&2a1b&0&uid4353#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}"
    private val pci = "10de:2684"

    private fun win(index: Int, name: String, key: String, x: Int, w: Int = 2560, adapter: Int = 0) = Monitor(
        index, "", x, 0, w, 1440, adapter = adapter, vendorId = 0x10DE, deviceName = name, adapterPciId = pci, key = key,
    )

    @Test
    fun `a screen that left the list is gone, and the screen now at its index is never taken for it`() {
        // Shared DISPLAY2 (output 1); it was switched off and DISPLAY3 is output 1 now
        val picked = win(1, "\\\\.\\DISPLAY2", dell, 2560).copy(name = "Screen 2")
        val now = listOf(win(0, "\\\\.\\DISPLAY1", "a", 0), win(1, "\\\\.\\DISPLAY3", lg, 2560))
        assertEquals(Located.Gone, ScreenCapture.locate(picked, now))
        val choice = ScreenCapture.Choice(picked, ScreenCapture.Plan(Source.DDAGRAB, Encoder.NVENC), emptyList())
        assertNull(ScreenCapture.rebind(choice, now))
    }

    @Test
    fun `a screen name handed to another monitor is another screen`() {
        // Win+P / docking gave \\.\DISPLAY2 to the LG while the Dell (picked) went away
        val picked = win(1, "\\\\.\\DISPLAY2", dell, 2560)
        val now = listOf(win(1, "\\\\.\\DISPLAY2", lg, 2560))
        assertEquals(Located.Gone, ScreenCapture.locate(picked, now))
        val choice = ScreenCapture.Choice(picked, ScreenCapture.Plan(Source.DDAGRAB, Encoder.NVENC), emptyList())
        assertNull(ScreenCapture.rebind(choice, now))
    }

    @Test
    fun `a mirrored screen stays itself while a monitor joins or leaves the mirror`() {
        val tv = "\\\\?\\display#sam0f9a#5&2a1b&0&uid4400#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}"
        val mirrored = ScreenCapture.monitorKey(listOf(dell, tv))
        val picked = win(0, "\\\\.\\DISPLAY1", mirrored, 0)
        // The TV was unplugged: DISPLAY1 shows the Dell alone
        assertTrue(ScreenCapture.locate(picked, listOf(win(0, "\\\\.\\DISPLAY1", dell, 0))) is Located.Here)
        // Picked alone, then a projector joined the mirror
        assertTrue(ScreenCapture.sameScreen(win(0, "\\\\.\\DISPLAY1", dell, 0), picked))
        // The name went to other monitors entirely
        assertEquals(Located.Gone, ScreenCapture.locate(picked, listOf(win(0, "\\\\.\\DISPLAY1", lg, 0))))
        assertFalse(ScreenCapture.keysShareMonitor(mirrored, ""))
        assertTrue(ScreenCapture.keysShareMonitor("", ""))
    }

    @Test
    fun `the picked screen is found at its new index and place, under the picked label`() {
        val picked = win(2, "\\\\.\\DISPLAY2", dell, 5120).copy(name = "Screen 3 (right)")
        val now = listOf(win(0, "\\\\.\\DISPLAY1", "a", 0), win(1, "\\\\.\\DISPLAY2", dell, 2560))
        val here = ScreenCapture.locate(picked, now) as Located.Here
        assertEquals(1, here.ways.single().index)
        assertEquals(2560, here.ways.single().x)
        assertEquals("Screen 3 (right)", here.ways.single().name)
    }

    @Test
    fun `nothing is found when the list cannot be read or the screen names nothing`() {
        assertEquals(Located.Unknown, ScreenCapture.locate(win(0, "\\\\.\\DISPLAY1", dell, 0), null))
        // A screen known only by an index (GLFW order, FFmpeg's probe) cannot be told apart from another
        val anonymous = Monitor(0, "Screen 1", 0, 0, 1920, 1080, adapter = 0)
        assertFalse(ScreenCapture.identifiable(anonymous))
        assertEquals(Located.Unknown, ScreenCapture.locate(anonymous, listOf(anonymous)))
        assertFalse(ScreenCapture.sameScreen(anonymous, anonymous))
    }

    @Test
    fun `monitor keys ignore case and order`() {
        assertEquals(ScreenCapture.monitorKey(listOf(lg, dell.uppercase())), ScreenCapture.monitorKey(listOf(dell, lg)))
        assertEquals("", ScreenCapture.monitorKey(emptyList()))
    }

    @Test
    fun `macos captures the picked display at its index in the active display list, not glfw's`() {
        // GLFW listed it first; after a hot-plug avfoundation counts it second
        val picked = Monitor(0, "DELL U2720Q", 0, 0, 3840, 2160, key = ScreenCapture.MAC_KEY + "69733382")
        assertEquals(1, ScreenCapture.avfoundationIndex(picked, listOf(1, 69733382, 5)))
        // Unplugged / mirrored away: not active, so not captured at all
        assertNull(ScreenCapture.avfoundationIndex(picked, listOf(1, 5)))
        // Display ids are unsigned 32-bit
        val high = picked.copy(key = ScreenCapture.MAC_KEY + "4294967295")
        assertEquals(0, ScreenCapture.avfoundationIndex(high, listOf(-1)))
        assertNull(ScreenCapture.avfoundationIndex(picked.copy(key = ""), listOf(69733382)))
    }

    @Test
    fun `a rectangle capture holds only while the same screen sits exactly at its rectangle`() {
        val picked = win(1, "\\\\.\\DISPLAY2", dell, 2560)
        val baseline = picked.copy(index = -1)
        assertTrue(ScreenCapture.rectStillShows(picked, baseline, baseline))
        // Another monitor slid into the rectangle (the middle one was unplugged)
        assertFalse(ScreenCapture.rectStillShows(picked, baseline, win(-1, "\\\\.\\DISPLAY3", lg, 2560)))
        // The screen moved or changed resolution: the fixed rectangle no longer frames it
        assertFalse(ScreenCapture.rectStillShows(picked, baseline, baseline.copy(x = 0)))
        assertFalse(ScreenCapture.rectStillShows(picked, baseline, baseline.copy(width = 1920)))
        // Nothing covers it any more, or the start could not be read
        assertFalse(ScreenCapture.rectStillShows(picked, baseline, null))
        assertFalse(ScreenCapture.rectStillShows(picked, null, baseline))
        // The screen at the rectangle at the start was not the picked one
        assertFalse(ScreenCapture.rectStillShows(picked, baseline.copy(key = lg), baseline.copy(key = lg)))
    }

    @Test
    fun `ffmpeg opens the gpu by its own index when it numbers the gpus differently from the game`() {
        // Two AMD GPUs, the game set to "High performance": the game lists the dGPU as 0, FFmpeg as 1
        val m = win(1, "\\\\.\\DISPLAY2", dell, 2560, adapter = 0).copy(ffmpegAdapter = 1)
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, m, Encoder.AMF, "srt://relay.example.com:8890?streamid=x")
        assertEquals("d3d11va=dd:1", a[a.indexOf("-init_hw_device") + 1])
        assertTrue(a.any { it.startsWith("ddagrab=output_idx=1:") })
        // The mapping stays with the screen as it moves between outputs
        val choice = ScreenCapture.Choice(m, ScreenCapture.Plan(Source.DDAGRAB, Encoder.AMF), emptyList())
        val moved = ScreenCapture.rebind(choice, listOf(win(0, "\\\\.\\DISPLAY2", dell, 0, adapter = 0)))!!
        assertEquals(0, moved.monitor.index)
        assertEquals(1, moved.monitor.ffmpegAdapter)
        assertEquals(1, ScreenCapture.adapterIndexFor(moved.monitor))
        assertEquals(0, ScreenCapture.adapterIndexFor(moved.monitor.copy(ffmpegAdapter = -1)))
    }

    @Test
    fun `x11 rectangle captures need the picked output at the same rectangle`() {
        val picked = Monitor(1, "DP-1", 1920, 0, 2560, 1440, key = "x11:DP-1")
        val hdmi = Monitor(0, "HDMI-1", 0, 0, 1920, 1080, key = "x11:HDMI-1")
        assertTrue(ScreenCapture.stillAt(picked, listOf(hdmi, picked.copy(index = 1))))
        // HDMI-1 unplugged: DP-1 slid to 0,0, and nothing is at 1920,0 any more
        assertFalse(ScreenCapture.stillAt(picked, listOf(picked.copy(index = 0, x = 0))))
        // Another output took the rectangle
        assertFalse(ScreenCapture.stillAt(picked, listOf(Monitor(0, "DP-2", 1920, 0, 2560, 1440, key = "x11:DP-2"))))
        // Found again (for a relaunch) at its new place
        val here = ScreenCapture.locate(picked, listOf(picked.copy(index = 0, x = 0))) as Located.Here
        assertEquals(0, here.ways.single().x)
    }
}
