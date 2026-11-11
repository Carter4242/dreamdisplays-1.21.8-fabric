package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.ScreenCapture.Encoder
import com.dreamdisplays.media.player.process.ScreenCapture.Plan
import com.dreamdisplays.media.player.process.ScreenCapture.Source
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real thing on a Windows machine: lists the screens from DXGI, chooses each one's plan with real `FFmpeg`
 * pipeline tests, then publishes a few seconds of every screen (auto plan, x264 forced, and the quiet retry after a
 * plan that fails at once) to local FLV files plus one PNG per run to check which screen it shows. Skipped unless
 * `DD_SCREEN_SHARE_FFMPEG` names an `FFmpeg`; `DD_SCREEN_SHARE_OUT` is where files go, `DD_SCREEN_SHARE_EXPECT` the
 * screen count to insist on.
 */
class ScreenCaptureLiveTest {
    private val ffmpeg = System.getenv("DD_SCREEN_SHARE_FFMPEG")
    private val out = File(System.getenv("DD_SCREEN_SHARE_OUT") ?: System.getProperty("java.io.tmpdir"), "dd-screen-share")

    @Test
    fun `every screen publishes on this machine`() {
        if (ffmpeg.isNullOrBlank() || !System.getProperty("os.name").startsWith("Windows")) return
        out.mkdirs()
        val t0 = System.nanoTime()
        val snap = Dxgi.enumerate()
        println("DXGI enumerate: ${(System.nanoTime() - t0) / 1_000} us")
        snap.adapters.forEach { println("adapter ${it.index}: ${it.description} ${it.pciId} luid=${java.lang.Long.toHexString(it.luid)} software=${it.software}") }
        snap.outputs.forEach {
            println("  output ${it.adapter.index}:${it.index} ${it.deviceName} (${it.left},${it.top})-(${it.right},${it.bottom}) attached=${it.attachedToDesktop}")
        }
        val t1 = System.nanoTime()
        val screens = assertNotNull(ScreenCapture.windowsScreens(ffmpeg))
        println("windowsScreens: ${(System.nanoTime() - t1) / 1_000} us")
        screens.forEach { println("screen: ${ScreenCapture.label(it)} ${it.deviceName} adapter #${it.adapter} ${it.adapterPciId} byVendor=${it.byVendor} output ${it.index}") }
        System.getenv("DD_SCREEN_SHARE_EXPECT")?.toIntOrNull()?.let { assertEquals(it, screens.size, "screens") }
        assertEquals(screens.size, screens.map { it.deviceName }.toSet().size, "one entry per Windows screen")

        val rows = ArrayList<String>()
        for ((i, screen) in screens.withIndex()) {
            for (software in listOf(false, true)) {
                val c0 = System.nanoTime()
                val choice = assertNotNull(ScreenCapture.choose(ffmpeg, screen, softwareOnly = software))
                val chooseMs = (System.nanoTime() - c0) / 1_000_000
                choice.attempts.forEach { println("    test ${it.plan} @ ${it.adapter}: ${if (it.ok) "ok" else "FAIL ${it.error.lines().lastOrNull()}"} (${it.elapsedMs} ms)") }
                val tag = "s${i + 1}_${if (software) "x264" else "auto"}"
                val ok = publish(choice.monitor, choice.plan, tag)
                rows += "| ${screen.name} | ${screen.deviceName} | ${if (software) "x264 forced" else "auto"} | ${choice.plan} @ adapter ${choice.monitor.adapter} (${choice.monitor.adapterPciId}) | ${chooseMs} ms | ${if (ok) "published" else "FAILED"} |"
                assertTrue(ok, "$tag publishes")
            }
            // The quiet retry: a plan that dies at once (another GPU's encoder) is replaced by the safe plan
            val wrong = when (screen.vendorId) { 0x1002, 0x1022 -> Encoder.NVENC; else -> Encoder.AMF }
            val bad = Plan(Source.DDAGRAB, wrong)
            val badResult = run(ScreenCapture.args(ffmpeg, bad.source, screen, bad.encoder, "rtmp://127.0.0.1:9/x").dropLast(3) +
                    listOf("-frames:v", "5", "-f", "null", "-"))
            val safe = ScreenCapture.safePlan(bad, screen, ScreenCapture.isEncoderOpenFailure(badResult.second))
            println("  forced $bad: exit ${badResult.first} encoderFailure=${ScreenCapture.isEncoderOpenFailure(badResult.second)} -> retry $safe")
            if (badResult.first != 0) {
                assertNotNull(safe)
                val ok = publish(screen, safe, "s${i + 1}_retry")
                rows += "| ${screen.name} | ${screen.deviceName} | runtime retry after $bad | $safe | - | ${if (ok) "published" else "FAILED"} |"
                assertTrue(ok, "retry publishes")
            }
            // GDI on its own, as used when Desktop Duplication cannot open a screen
            val gdi = Plan(Source.GDIGRAB, Encoder.X264)
            val ok = publish(screen, gdi, "s${i + 1}_gdi")
            rows += "| ${screen.name} | ${screen.deviceName} | gdigrab forced | $gdi | - | ${if (ok) "published" else "FAILED"} |"
            assertTrue(ok, "gdi publishes")
        }
        println("RESULTS")
        rows.forEach(::println)
    }

    /** Publishes ~2 s of [monitor] with [plan] to an FLV file through the real share argv, then saves its first frame. */
    private fun publish(monitor: ScreenCapture.Monitor, plan: Plan, tag: String): Boolean {
        val flv = File(out, "$tag.flv").apply { delete() }
        val argv = ScreenCapture.args(ffmpeg!!, plan.source, monitor, plan.encoder, "rtmp://127.0.0.1:9/x").dropLast(3) +
                listOf("-frames:v", "60", "-f", "flv", flv.absolutePath)
        val (code, output) = run(argv)
        if (code != 0 || !flv.isFile || flv.length() < 1_000) {
            println("  $tag $plan FAILED exit $code: ${ScreenCapture.errorTail(output)}")
            return false
        }
        val png = File(out, "$tag.png").apply { delete() }
        run(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", flv.absolutePath, "-frames:v", "1", "-vf", "scale=480:-2", png.absolutePath))
        println("  $tag $plan published ${flv.length() / 1024} KB -> ${png.name}")
        return true
    }

    private fun run(argv: List<String>): Pair<Int, String> {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        p.outputStream.close()
        val text = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { text.appendLine(it) } }.apply { start() }
        if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
        reader.join(2_000)
        return (if (p.isAlive) -1 else p.exitValue()) to text.toString()
    }
}
