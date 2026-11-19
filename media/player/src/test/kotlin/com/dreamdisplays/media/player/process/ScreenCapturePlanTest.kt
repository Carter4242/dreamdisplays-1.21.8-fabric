package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.ScreenCapture.Encoder
import com.dreamdisplays.media.player.process.ScreenCapture.Monitor
import com.dreamdisplays.media.player.process.ScreenCapture.Plan
import com.dreamdisplays.media.player.process.ScreenCapture.ProbeResult
import com.dreamdisplays.media.player.process.ScreenCapture.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a Windows screen's plan is chosen from its pipeline tests ([ScreenCapture.planFor]), and what a failed test reports. */
class ScreenCapturePlanTest {
    private val nvidia = "10de:2504"
    private val screen = Monitor(0, "Screen 1 (main)", 0, 0, 2560, 1440, adapter = 0, vendorId = 0x10DE,
        deviceName = "\\\\.\\DISPLAY1", adapterPciId = nvidia, key = "\\\\?\\display#gsm5b7f")
    private val byVendor = screen.copy(byVendor = true)

    /** What every ddagrab test printed on the RTX 3060 whose share froze: no frame, only the aftermath. */
    private val noFrame = """
        [Parsed_ddagrab_0 @ 000001d2c0f0e2c0] Using provided hw_device_ctx
        [Parsed_ddagrab_0 @ 000001d2c0f0e2c0] Invalid output duplication argument
        [Parsed_ddagrab_0 @ 000001d2c0f0e2c0] Failed to configure output pad on Parsed_ddagrab_0
        [fc#0 @ 000001d2c0e4a480] Error configuring filter graph: Invalid argument
        [fc#0 @ 000001d2c0e4a480] Task finished with error code: -22 (Invalid argument)
        [fc#0 @ 000001d2c0e4a480] Terminating thread with return code -22 (Invalid argument)
        [vost#0:0/h264_nvenc @ 000001d2c0f3a100] [enc:h264_nvenc @ 000001d2c0e4c1c0] Could not open encoder before EOF
        [vost#0:0/h264_nvenc @ 000001d2c0f3a100] Task finished with error code: -22 (Invalid argument)
        [vost#0:0/h264_nvenc @ 000001d2c0f3a100] Terminating thread with return code -22 (Invalid argument)
        [out#0/null @ 000001d2c0e48b40] Nothing was written into output file, because at least one of its streams received no packets.
        Conversion failed!
    """.trimIndent()

    private val nvencTooOld = """
        [h264_nvenc @ 000002caeae6d140] Driver does not support the required nvenc API version. Required: 13.1 Found: 13.0
        [vost#0:0/h264_nvenc @ 000002caeae6cec0] [enc:h264_nvenc @ 000002caeadebc80] Error while opening encoder - maybe incorrect parameters such as bit_rate, rate, width or height.
        [vost#0:0/h264_nvenc @ 000002caeae6cec0] [enc:h264_nvenc @ 000002caeadebc80] Could not open encoder before EOF
        Conversion failed!
    """.trimIndent()

    private class Fake(
        val wgc: Boolean = true,
        val hmonitor: Long? = 0x1234L,
        val encoders: List<Encoder> = [Encoder.NVENC, Encoder.X264],
        val result: (Plan, Monitor) -> Pair<Boolean, String>,
    ) : ScreenCapture.PlanProbes {
        val ran = ArrayList<Pair<Plan, Monitor>>()
        override fun encoders(monitor: Monitor) = encoders
        override fun probe(plan: Plan, monitor: Monitor): ProbeResult {
            ran += plan to monitor
            val (ok, output) = result(plan, monitor)
            return ProbeResult(ok, null, if (ok) "" else ScreenCapture.errorTail(output), 300, output)
        }
        override fun ffmpegAdapter(pciId: String, skip: Int): Int? = null
        override val screenWgc: Boolean get() = wgc
        override fun hmonitor(monitor: Monitor) = hmonitor
    }

    @Test
    fun `desktop duplication wins where it gives a frame`() {
        val fake = Fake { plan, _ -> (plan.source == Source.DDAGRAB) to "" }
        val choice = ScreenCapture.planFor([byVendor, screen], fake)
        assertTrue(choice.works)
        assertEquals(Plan(Source.DDAGRAB, Encoder.NVENC), choice.plan)
        assertEquals(byVendor, choice.monitor)
        assertEquals(1, fake.ran.size)
    }

    @Test
    fun `a screen desktop duplication gives no frame falls back to windows graphics capture, never gdi`() {
        // The frozen-share case: every ddagrab test ends with only "Could not open encoder before EOF"
        val fake = Fake { plan, _ -> if (plan.source == Source.GFXCAPTURE) true to "" else false to noFrame }
        val choice = ScreenCapture.planFor([byVendor, screen], fake)
        assertTrue(choice.works)
        assertEquals(Plan(Source.GFXCAPTURE, Encoder.NVENC), choice.plan)
        // The capture failing is no encoder failure: one ddagrab test per way to the screen, not one per encoder
        assertEquals(
            [Plan(Source.DDAGRAB, Encoder.NVENC), Plan(Source.DDAGRAB, Encoder.NVENC), Plan(Source.GFXCAPTURE, Encoder.NVENC)],
            fake.ran.map { it.first },
        )
        // Tested on its HMONITOR, kept without it (each capture looks it up again)
        assertEquals(0x1234L, fake.ran.last().second.hmonitor)
        assertEquals(0L, choice.monitor.hmonitor)
        assertFalse(fake.ran.any { it.first.source == Source.GDIGRAB })
        // Why ddagrab failed is in the attempts, its own line first
        val dd = choice.attempts.first()
        assertFalse(dd.ok)
        assertTrue(dd.error.startsWith("[ddagrab] Invalid output duplication argument"), dd.error)
    }

    @Test
    fun `windows graphics capture tries the encoders in order too`() {
        val fake = Fake { plan, _ ->
            when {
                plan.source == Source.DDAGRAB -> false to noFrame
                plan.encoder == Encoder.NVENC -> false to nvencTooOld
                else -> true to ""
            }
        }
        val choice = ScreenCapture.planFor([screen], fake)
        assertEquals(Plan(Source.GFXCAPTURE, Encoder.X264), choice.plan)
        assertEquals([Plan(Source.DDAGRAB, Encoder.NVENC), Plan(Source.GFXCAPTURE, Encoder.NVENC), Plan(Source.GFXCAPTURE, Encoder.X264)],
            fake.ran.map { it.first })
    }

    @Test
    fun `an encoder that will not open moves on to the next encoder on the same capture`() {
        val fake = Fake { plan, _ -> if (plan.encoder == Encoder.NVENC) false to nvencTooOld else true to "" }
        val choice = ScreenCapture.planFor([screen], fake)
        assertEquals(Plan(Source.DDAGRAB, Encoder.X264), choice.plan)
    }

    @Test
    fun `a screen neither source captures is unusable, with every test kept for the report`() {
        val fake = Fake { _, _ -> false to noFrame }
        val choice = ScreenCapture.planFor([byVendor, screen], fake, nowMs = { 1_000L })
        assertFalse(choice.works)
        assertEquals(1_000L, choice.checkedAtMs)
        assertEquals(3, choice.attempts.size)
        assertTrue(choice.attempts.none { it.ok })
        assertFalse(fake.ran.any { it.first.source == Source.GDIGRAB })
        // No HMONITOR, or a build without screen gfxcapture: unusable too, saying why
        val noHandle = ScreenCapture.planFor([screen], Fake(hmonitor = null) { p, _ -> (p.source == Source.GFXCAPTURE) to noFrame })
        assertFalse(noHandle.works)
        assertTrue("HMONITOR" in noHandle.attempts.last().error)
        val oldBuild = ScreenCapture.planFor([screen], Fake(wgc = false) { p, _ -> (p.source == Source.GFXCAPTURE) to noFrame })
        assertFalse(oldBuild.works)
        assertEquals(Source.GFXCAPTURE, oldBuild.attempts.last().plan.source)
    }

    @Test
    fun `an unusable screen is tested again after a while, a working one never`() {
        val bad = ScreenCapture.Choice(screen, Plan(Source.DDAGRAB, Encoder.X264), [], works = false, checkedAtMs = 10_000L)
        assertTrue(ScreenCapture.needsCheck(null))
        assertFalse(ScreenCapture.needsCheck(bad, nowMs = 10_000L + ScreenCapture.RECHECK_UNUSABLE_MS - 1))
        assertTrue(ScreenCapture.needsCheck(bad, nowMs = 10_000L + ScreenCapture.RECHECK_UNUSABLE_MS))
        assertFalse(ScreenCapture.needsCheck(bad.copy(works = true), nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `the error tail keeps the capture source's own lines, ahead of the aftermath`() {
        val tail = ScreenCapture.errorTail(noFrame)
        val lines = tail.lines()
        assertEquals("[ddagrab] Invalid output duplication argument", lines[0])
        assertEquals("[ddagrab] Failed to configure output pad on Parsed_ddagrab_0", lines[1])
        assertEquals("[fc#0] Error configuring filter graph: Invalid argument", lines[2])
        // What every failed run prints after the cause stays out, as does the device hand-over
        assertFalse("Could not open encoder before EOF" in tail, tail)
        assertFalse("Conversion failed" in tail, tail)
        assertFalse("hw_device_ctx" in tail, tail)
        // The cause survives the 300-character cut of a report's attempt
        assertTrue(tail.take(300).contains("Invalid output duplication argument"))
        // How far the capture got shows too (verbose lines of the source with no error word)
        val probed = "[Parsed_ddagrab_0 @ 0x1] Opened dxgi output 0 with dimensions 2560x1440\n" +
            "[Parsed_ddagrab_0 @ 0x1] Probed 16 bit float RGB frame format\n" + noFrame.lines().drop(3).joinToString("\n")
        assertTrue(ScreenCapture.errorTail(probed).startsWith("[ddagrab] Opened dxgi output 0"), ScreenCapture.errorTail(probed))
        assertTrue("Probed 16 bit float" in ScreenCapture.errorTail(probed))
        // Nothing but the aftermath: that is what is shown
        val bare = "[vost#0:0/libx264 @ 0x2] Could not open encoder before EOF\nConversion failed!"
        assertEquals("[vost#0:0/libx264] Could not open encoder before EOF\nConversion failed!", ScreenCapture.errorTail(bare))
        // Encoder failures still show the encoder's own words
        assertTrue("Driver does not support the required nvenc API version" in ScreenCapture.errorTail(nvencTooOld))
        assertTrue(ScreenCapture.isEncoderOpenFailure(nvencTooOld))
        assertFalse(ScreenCapture.isEncoderOpenFailure(noFrame))
    }
}
