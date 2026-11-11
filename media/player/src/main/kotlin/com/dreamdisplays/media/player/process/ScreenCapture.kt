package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Builds and probes the `FFmpeg` screen-capture + encode process behind the built-in "Share screen": one monitor,
 * captured at [Settings.fps], scaled to fit [Settings.maxHeight], encoded for the lowest latency the machine offers
 * (hardware H.264 when it works, else x264 `ultrafast` / `zerolatency`) and pushed to a live relay as MPEG-TS over SRT
 * (FLV for RTMP). Video only: `FFmpeg` has no built-in way to capture what the desktop plays.
 *
 * On Windows each screen gets its own [Plan]: the first capture source and encoder that run that screen's real
 * pipeline (Desktop Duplication on the screen's own graphics adapter with that GPU's encoder, then the other hardware
 * encoders, then x264; GDI capture if Desktop Duplication cannot open the screen at all).
 */
object ScreenCapture {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Where the picture comes from. [GDIGRAB] is the Windows fallback when Desktop Duplication cannot open a screen. */
    enum class Source(val ffmpegName: String) {
        DDAGRAB("ddagrab"), GDIGRAB("gdigrab"), AVFOUNDATION("avfoundation"), X11GRAB("x11grab")
    }

    /**
     * One monitor. [index] is its position in GLFW's list (the primary first), or for Desktop Duplication the output
     * index on graphics adapter [adapter] (-1: the default adapter). [vendorId] is that adapter's PCI vendor (0:
     * unknown); with [byVendor] `FFmpeg` opens the adapter by that vendor rather than by index (immune to a process
     * seeing the adapters in another order). [deviceName] is the Windows screen (`\\.\DISPLAY1`) when known;
     * [x], [y], [width], [height] are its desktop rectangle unless [rectKnown] is false.
     */
    data class Monitor(
        val index: Int, val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val adapter: Int = -1,
        val vendorId: Int = 0, val byVendor: Boolean = false, val deviceName: String = "", val rectKnown: Boolean = true,
        /** PCI `vvvv:dddd` of [adapter] and its name, when known. */
        val adapterPciId: String = "", val adapterName: String = "",
    )

    /** H.264 encoders tried in order; the first that encodes a test picture here wins. */
    enum class Encoder(val ffmpegName: String, val hardware: Boolean) {
        NVENC("h264_nvenc", true),
        AMF("h264_amf", true),
        QSV("h264_qsv", true),
        VIDEOTOOLBOX("h264_videotoolbox", true),
        X264("libx264", false),
    }

    /** What to capture and how hard to encode it. */
    data class Settings(
        val fps: Int = 30,
        val bitrateKbps: Int = 4_000,
        val maxWidth: Int = 1920,
        val maxHeight: Int = 1080,
        /** SRT latency (ms) for the publisher's leg when the publish URL sets none. */
        val latencyMs: Int = 60,
    ) {
        /** These settings within a server's limits (0: no limit): fewer frames, a smaller picture, the bitrate scaled. */
        fun limitedTo(maxFps: Int, maxHeight: Int): Settings {
            val f = if (maxFps > 0) fps.coerceAtMost(maxFps.coerceAtLeast(1)) else fps
            val h = if (maxHeight > 0) this.maxHeight.coerceAtMost(maxHeight.coerceAtLeast(144)) else this.maxHeight
            val w = (maxWidth.toLong() * h / this.maxHeight).toInt() and 1.inv()
            val kbps = (bitrateKbps.toLong() * f * h / (fps.toLong() * this.maxHeight)).toInt().coerceAtLeast(500)
            return copy(fps = f, maxWidth = w, maxHeight = h and 1.inv(), bitrateKbps = kbps.coerceAtMost(bitrateKbps))
        }
    }

    /** How one screen is shared: where the picture comes from and what encodes it. */
    data class Plan(val source: Source, val encoder: Encoder) {
        override fun toString() = "${source.ffmpegName}+${encoder.ffmpegName}"
    }

    /** One pipeline test while choosing a [Plan]: [adapter] as `FFmpeg` was told to open it, [error] when it failed. */
    data class Attempt(val plan: Plan, val adapter: String, val ok: Boolean, val error: String = "", val elapsedMs: Long = 0)

    /** The [Plan] chosen for a screen, the exact [monitor] variant it runs on, and the tests behind it. */
    data class Choice(val monitor: Monitor, val plan: Plan, val attempts: List<Attempt>)

    /** The capture source on this OS, or null where there is none (e.g. Linux under Wayland only). */
    fun sourceForThisOs(): Source? = when {
        OsInfo.isWindows -> Source.DDAGRAB
        OsInfo.isMac -> Source.AVFOUNDATION
        OsInfo.isLinux && !System.getenv("DISPLAY").isNullOrBlank() -> Source.X11GRAB
        else -> null
    }

    /** Encoders worth probing on this OS, best first; [Encoder.X264] always comes last. */
    fun candidates(): List<Encoder> = when {
        OsInfo.isWindows -> [Encoder.NVENC, Encoder.AMF, Encoder.QSV, Encoder.X264]
        OsInfo.isMac -> [Encoder.VIDEOTOOLBOX, Encoder.X264]
        else -> [Encoder.NVENC, Encoder.X264]
    }

    @Volatile
    private var usable: Pair<String, List<Encoder>>? = null

    /**
     * True when the server-supplied [publishUrl] is a relay this client may stream the screen to: a well-formed live
     * relay URL (`srt` / `rtmp(s)` / `rtsp`) on a public host. Without it `FFmpeg` takes any output: a local file
     * path, `file:`, `http://` or `tcp://` to a host on the player's LAN. Resolves the host (call off the render thread).
     */
    fun publishTargetAllowed(publishUrl: String): Boolean =
        LiveStreamUrls.isValid(publishUrl) && MediaHostGuard.isAllowed(publishUrl)

    /**
     * The encoders in [candidates] that open at all on this machine, best first, [Encoder.X264] last (a build lists
     * `h264_nvenc` whether or not there is an NVIDIA GPU, or a driver new enough for it). Blocks for a few short
     * `FFmpeg` runs the first time; call off the render thread.
     */
    fun usableEncoders(ffmpeg: String): List<Encoder> {
        usable?.takeIf { it.first == ffmpeg }?.let { return it.second }
        val list = candidates().filter { it == Encoder.X264 || works(ffmpeg, it) }
        logger.info("Screen share encoders that open here: {}.", list.joinToString { it.ffmpegName })
        usable = ffmpeg to list
        return list
    }

    /** The best encoder that opens here, wherever the picture comes from (macOS / Linux). */
    fun pickEncoder(ffmpeg: String): Encoder = usableEncoders(ffmpeg).first()

    private fun works(ffmpeg: String, encoder: Encoder): Boolean = exec(
        listOf(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostats",
            "-f", "lavfi", "-i", "color=c=black:s=640x360:r=30", "-frames:v", "3",
            "-pix_fmt", "nv12", "-c:v", encoder.ffmpegName, "-f", "null", "-",
        )
    ).also { if (!it.ok) logger.debug("Encoder {} unusable here: {}", encoder.ffmpegName, it.tail) }.ok

    // ---- Per-screen plans ----

    private val choices = ConcurrentHashMap<String, Choice>()

    private fun choiceKey(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean) =
        "$ffmpeg|$softwareOnly|" + monitor.deviceName.ifEmpty { "${monitor.adapter}:${monitor.index}@${monitor.x},${monitor.y}" }

    /** The plan already chosen for [monitor], or null before [choose] ran for it. */
    fun knownChoice(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean = false): Choice? =
        choices[choiceKey(ffmpeg, monitor, softwareOnly)]

    /** The share of [monitor] ran fine with [choice] (e.g. a fallback after the first plan failed): keep it. */
    fun rememberChoice(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean, choice: Choice) {
        choices[choiceKey(ffmpeg, monitor, softwareOnly)] = choice
    }

    /**
     * How to share [monitor]: the first source and encoder that encode two frames of its real pipeline. On macOS and
     * Linux that is the OS source with the best encoder. On Windows: Desktop Duplication on the screen's own adapter
     * (or a duplicate of the screen on another adapter) with that GPU's encoder, the other hardware encoders, then
     * x264; then GDI capture. [softwareOnly] (the server asked for it) tries x264 only. Cached per screen; blocks for
     * a few `FFmpeg` runs the first time, so call off the render thread.
     */
    fun choose(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean = false): Choice {
        knownChoice(ffmpeg, monitor, softwareOnly)?.let { return it }
        val source = sourceForThisOs() ?: Source.DDAGRAB
        val choice = if (source != Source.DDAGRAB) {
            Choice(monitor, Plan(source, if (softwareOnly) Encoder.X264 else pickEncoder(ffmpeg)), [])
        } else {
            chooseOnWindows(ffmpeg, monitor, softwareOnly)
        }
        logger.info(
            "Screen share plan for {}: {} on {} (tried {}).", monitor.deviceName.ifEmpty { monitor.name },
            choice.plan, adapterLabel(choice.monitor), choice.attempts.joinToString { "${it.plan}@${it.adapter}=${if (it.ok) "ok" else "fail"}" },
        )
        choices[choiceKey(ffmpeg, monitor, softwareOnly)] = choice
        return choice
    }

    private fun chooseOnWindows(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean): Choice {
        val attempts = ArrayList<Attempt>()
        val variants = variantsOf(monitor)
        // Desktop Duplication, on each adapter that shows this screen
        for (m in variants) {
            for (encoder in encodersFor(ffmpeg, m, softwareOnly)) {
                val plan = Plan(Source.DDAGRAB, encoder)
                val r = probePipeline(ffmpeg, plan, m)
                val wrongAdapter = r.pciId != null && m.adapterPciId.isNotEmpty() && r.pciId != m.adapterPciId
                val ok = r.ok && !wrongAdapter
                val error = if (wrongAdapter) "opened ${r.pciId}, not ${m.adapterPciId}" else r.tail
                attempts += Attempt(plan, adapterLabel(m), ok, if (ok) "" else error, r.elapsedMs)
                if (ok) return Choice(m, plan, attempts)
                // The capture itself (or the adapter) failed: no other encoder will help on this variant
                if (wrongAdapter || !isEncoderOpenFailure(r.tail)) break
            }
        }
        // GDI: copies the desktop rectangle, any adapter; hardware encoders then open a device of their own
        val first = variants.first()
        if (first.rectKnown && first.width > 0 && first.height > 0) {
            for (encoder in encodersFor(ffmpeg, first, softwareOnly)) {
                val plan = Plan(Source.GDIGRAB, encoder)
                val r = probePipeline(ffmpeg, plan, first)
                attempts += Attempt(plan, "gdi", r.ok, if (r.ok) "" else r.tail, r.elapsedMs)
                if (r.ok) return Choice(first, plan, attempts)
                if (!isEncoderOpenFailure(r.tail)) break
            }
            return Choice(first, Plan(Source.GDIGRAB, Encoder.X264), attempts)
        }
        return Choice(first, Plan(Source.DDAGRAB, Encoder.X264), attempts)
    }

    /** Hardware encoders for [monitor]'s GPU first (on another GPU's device they fail), then the others, then x264. */
    private fun encodersFor(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean): List<Encoder> {
        if (softwareOnly) return [Encoder.X264]
        val own = when (monitor.vendorId) {
            0x10DE -> Encoder.NVENC
            0x1002, 0x1022 -> Encoder.AMF
            0x8086 -> Encoder.QSV
            else -> null
        }
        return usableEncoders(ffmpeg).sortedBy { if (it == own) 0 else if (it.hardware) 1 else 2 }
    }

    /** The safe plan to retry with after [plan] failed at once: x264, and GDI capture too unless only the encoder failed. */
    fun safePlan(plan: Plan, monitor: Monitor, encoderFailed: Boolean): Plan? {
        val gdi = plan.source == Source.DDAGRAB && monitor.rectKnown && monitor.width > 0
        return when {
            plan.encoder != Encoder.X264 && (encoderFailed || !gdi) -> Plan(plan.source, Encoder.X264)
            gdi -> Plan(Source.GDIGRAB, Encoder.X264)
            plan.encoder != Encoder.X264 -> Plan(plan.source, Encoder.X264)
            else -> null
        }
    }

    internal data class ProbeResult(val ok: Boolean, val pciId: String?, val tail: String, val elapsedMs: Long)

    /** Encodes two frames of [plan] on [monitor] to nowhere. */
    internal fun probePipeline(ffmpeg: String, plan: Plan, monitor: Monitor, settings: Settings = Settings()): ProbeResult {
        val args: List<String> = listOf(ffmpeg, "-hide_banner", "-loglevel", "verbose", "-nostats") +
                inputArgs(plan.source, monitor, plan.encoder, settings) + listOf("-an") + encoderArgs(plan.encoder, settings) +
                listOf("-frames:v", "2", "-f", "null", "-")
        val r = exec(args)
        val pci = USING_DEVICE.find(r.output)?.let { "${it.groupValues[1]}:${it.groupValues[2]}".lowercase() }
        return ProbeResult(r.ok, pci, r.tail, r.elapsedMs)
    }

    private class RunResult(val ok: Boolean, val output: String, val tail: String, val elapsedMs: Long)

    private fun exec(args: List<String>, timeoutSeconds: Long = 10): RunResult {
        val t0 = System.nanoTime()
        return runCatching {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            runCatching { p.outputStream.close() }
            val out = StringBuilder()
            val reader = Thread({
                runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 64_000) out.appendLine(it) } }
            }, "DreamDisplays-ffmpeg-probe-out").apply { isDaemon = true; start() }
            val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            reader.join(1_000)
            val text = out.toString()
            val ms = (System.nanoTime() - t0) / 1_000_000
            RunResult(done && p.exitValue() == 0, text, if (done) errorTail(text) else "timed out after ${timeoutSeconds}s", ms)
        }.getOrElse { RunResult(false, "", it.message ?: it.javaClass.simpleName, (System.nanoTime() - t0) / 1_000_000) }
    }

    /** The lines of `FFmpeg` output that say what went wrong (at most the last 4). */
    fun errorTail(output: String): String = output.lineSequence()
        .filter { ERROR_LINE.containsMatchIn(it) }
        .toList().takeLast(4).joinToString("\n") { it.trim().take(300) }

    private val ERROR_LINE = Regex("(?i)error|fail|unable|cannot|could not|not support|no such|invalid|denied|unsupported")
    private val USING_DEVICE = Regex("""Using device ([0-9a-fA-F]{4}):([0-9a-fA-F]{4})""")

    /**
     * True when `FFmpeg`'s [output] says the encoder would not open (or its device), as opposed to the capture or the
     * relay failing.
     */
    fun isEncoderOpenFailure(output: String): Boolean = ENCODER_OPEN_FAILURE.containsMatchIn(output)

    private val ENCODER_OPEN_FAILURE = Regex(
        "Error while opening encoder|Could not open encoder|Failed to create derived|AMF failed to initiali[sz]e|" +
                "nvenc API version|OpenEncodeSessionEx failed|No NVENC capable devices|Error creating a MFX session|" +
                "Cannot load nvcuda|Cannot load amfrt|\\[(?:h264_(?:nvenc|amf|qsv|videotoolbox)|libx264)[^\\]]*\\][^\\n]*(?:error|fail)",
        RegexOption.IGNORE_CASE,
    )

    // ---- Arguments ----

    /** Full `FFmpeg` argv capturing [monitor] with [source], encoding with [encoder], publishing to [publishUrl]. */
    fun args(
        ffmpeg: String, source: Source, monitor: Monitor, encoder: Encoder, publishUrl: String,
        settings: Settings = Settings(),
    ): List<String> = buildList {
        add(ffmpeg)
        addAll(["-hide_banner", "-loglevel", "error", "-nostats"])
        addAll(inputArgs(source, monitor, encoder, settings))
        val fps = settings.fps.toString()
        add("-an")
        addAll(encoderArgs(encoder, settings))
        // A keyframe every second (a viewer joining waits at most that long) and no B-frames (each one is a frame
        // of delay at the encoder)
        addAll(["-g", fps, "-keyint_min", fps, "-bf", "0"])
        val target = LiveStreamUrls.publishUrl(publishUrl, settings.latencyMs)
        val flv = LiveStreamUrls.schemeOf(target)?.startsWith("rtmp") == true
        addAll(["-flush_packets", "1", "-muxdelay", "0", "-muxpreload", "0"])
        // MPEG-TS: a video PES with its length written goes out only once the frame is complete (~30 ms later)
        if (!flv) addAll(["-omit_video_pes_length", "0"])
        addAll(["-f", if (flv) "flv" else "mpegts", target])
    }

    /** The capture half of [args]: where the picture comes from, scaled and converted for [encoder]. */
    internal fun inputArgs(source: Source, monitor: Monitor, encoder: Encoder, settings: Settings): List<String> = buildList {
        val fps = settings.fps.toString()
        val fit = "scale=w='min(${settings.maxWidth},iw)':h='min(${settings.maxHeight},ih)'" +
                ":force_original_aspect_ratio=decrease:force_divisible_by=2:flags=fast_bilinear"
        val pixFmt = if (encoder.hardware) "nv12" else "yuv420p"
        when (source) {
            Source.DDAGRAB -> {
                // Desktop Duplication: the GPU hands over each new desktop frame (repeating the last one while the
                // screen is still, so the stream never pauses); downloaded once, scaled on the CPU. It only sees the
                // screens of one graphics adapter, so a screen on another one names its adapter. The source has to sit
                // in -filter_complex: as `-f lavfi -i ddagrab` it never gets -filter_hw_device and always opens the
                // default adapter, whichever screen was asked for.
                addAll(adapterArgs(monitor))
                addAll(["-filter_complex",
                    "ddagrab=output_idx=${monitor.index}:framerate=$fps:draw_mouse=1,hwdownload,format=bgra,$fit,format=$pixFmt[v]",
                    "-map", "[v]"])
            }

            Source.GDIGRAB -> {
                // GDI: copies the screen's rectangle of the desktop (primary screen's top left is 0,0); slower than
                // Desktop Duplication but works where it cannot (old drivers, remote sessions)
                addAll(["-f", "gdigrab", "-draw_mouse", "1", "-framerate", fps])
                addAll(["-offset_x", monitor.x.toString(), "-offset_y", monitor.y.toString()])
                addAll(["-video_size", "${monitor.width}x${monitor.height}", "-i", "desktop"])
                addAll(["-vf", "$fit,format=$pixFmt"])
            }

            Source.AVFOUNDATION -> {
                addAll(["-f", "avfoundation", "-capture_cursor", "1", "-framerate", fps])
                addAll(["-i", "Capture screen ${monitor.index}:none"])
                addAll(["-vf", "$fit,format=$pixFmt"])
            }

            Source.X11GRAB -> {
                val display = System.getenv("DISPLAY").orEmpty().ifBlank { ":0" }
                addAll(["-f", "x11grab", "-draw_mouse", "1", "-framerate", fps])
                addAll(["-video_size", "${monitor.width}x${monitor.height}"])
                addAll(["-i", "$display+${monitor.x},${monitor.y}"])
                addAll(["-vf", "$fit,format=$pixFmt"])
            }
        }
    }

    /** `FFmpeg`'s D3D11 device on [monitor]'s adapter, by vendor or index; none for the default adapter. */
    private fun adapterArgs(monitor: Monitor): List<String> {
        val device = when {
            monitor.byVendor && monitor.vendorId != 0 -> String.format(Locale.ROOT, "dd:,vendor_id=0x%04x", monitor.vendorId)
            monitor.adapter >= 0 -> "dd:${monitor.adapter}"
            else -> return []
        }
        return ["-init_hw_device", "d3d11va=$device", "-filter_hw_device", "dd"]
    }

    private fun adapterLabel(monitor: Monitor): String = when {
        monitor.byVendor && monitor.vendorId != 0 -> String.format(Locale.ROOT, "vendor 0x%04x", monitor.vendorId)
        monitor.adapter >= 0 -> "#${monitor.adapter}"
        else -> "default"
    }

    /** Rate control and latency options for [encoder]: constant bitrate, no lookahead, no frame reordering. */
    internal fun encoderArgs(encoder: Encoder, settings: Settings): List<String> {
        val rate = "${settings.bitrateKbps}k"
        // A quarter second of VBV: keyframes cannot balloon into a burst that the relay has to queue
        val buffer = "${settings.bitrateKbps / 4}k"
        val cbr = ["-b:v", rate, "-maxrate", rate, "-bufsize", buffer]
        return when (encoder) {
            Encoder.NVENC -> ["-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0",
                "-rc", "cbr", "-profile:v", "main"] + cbr

            Encoder.AMF -> ["-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-rc", "cbr",
                "-profile:v", "main"] + cbr

            Encoder.QSV -> ["-c:v", "h264_qsv", "-preset", "veryfast", "-async_depth", "1", "-profile:v", "main"] + cbr

            Encoder.VIDEOTOOLBOX -> ["-c:v", "h264_videotoolbox", "-realtime", "1", "-prio_speed", "1",
                "-profile:v", "main"] + cbr

            Encoder.X264 -> ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-profile:v", "main",
                "-x264-params", "nal-hrd=cbr"] + cbr
        }
    }

    // ---- Windows screens ----

    /**
     * Every screen on the desktop, from DXGI (see [Dxgi]): one per Windows screen, named by where it sits ("Screen 1
     * (left)", ordered left to right), on the adapter whose [Plan] worked when [choose] already ran for it. Cheap
     * (no `FFmpeg`): call whenever the picker opens. Null when DXGI cannot be read here.
     */
    fun windowsScreens(ffmpeg: String?): List<Monitor>? {
        val groups = dxgiGroups() ?: return null
        val screens = groups.map { variants ->
            val chosen = ffmpeg?.let { f -> variants.firstNotNullOfOrNull { knownChoice(f, it)?.monitor } }
            chosen ?: variants.first()
        }
        return named(screens)
    }

    /** GPU names DXGI lists (software adapters left out), for reports. */
    fun gpuNames(): List<String> = runCatching {
        Dxgi.enumerate().adapters.filterNot { it.software }.map { "${it.description} (${it.pciId})" }
    }.getOrDefault([])

    /** One line per screen DXGI lists, `adapter#:output vendor DeviceName rect`, for reports. */
    fun screensSummary(): List<String> = runCatching {
        Dxgi.enumerate().outputs.map { o ->
            "${o.adapter.index}:${o.index} ${o.adapter.pciId} ${o.deviceName} ${o.left},${o.top} ${o.width}x${o.height}" +
                    if (o.attachedToDesktop) "" else " detached"
        }
    }.getOrDefault([])

    /**
     * Each attached screen's ways to reach it: one entry per adapter that lists it (DXGI order), opened by vendor
     * when that vendor has a single adapter (then by index as a second try), else by index.
     */
    private fun dxgiGroups(): List<List<Monitor>>? {
        val snap = runCatching { Dxgi.enumerate() }
            .onFailure { logger.debug("DXGI screen list unavailable: {}", it.toString()) }.getOrNull() ?: return null
        val perVendor = snap.adapters.filterNot { it.software }.groupingBy { it.vendorId }.eachCount()
        val groups = snap.outputs
            .filter { it.attachedToDesktop && it.width > 0 && it.height > 0 }
            .groupBy { it.deviceName.ifEmpty { "${it.adapter.index}:${it.index}" } }
        return groups.values.map { outputs ->
            outputs.flatMap { o ->
                val base = Monitor(
                    index = o.index, name = "", x = o.left, y = o.top, width = o.width, height = o.height,
                    adapter = o.adapter.index, vendorId = o.adapter.vendorId, deviceName = o.deviceName,
                    adapterPciId = o.adapter.pciId, adapterName = o.adapter.description,
                )
                if (perVendor[o.adapter.vendorId] == 1) [base.copy(byVendor = true), base] else [base]
            }
        }
    }

    /** Every way to reach [monitor]'s screen, [monitor] itself first. */
    private fun variantsOf(monitor: Monitor): List<Monitor> {
        if (monitor.deviceName.isEmpty()) return [monitor]
        val group = dxgiGroups()?.firstOrNull { g -> g.any { it.deviceName == monitor.deviceName } }.orEmpty()
        val withName = { m: Monitor -> m.copy(name = monitor.name) }
        return ([monitor] + group.map(withName)).distinct()
    }

    /** Names screens by their place on the desktop, left to right (top to bottom for a column of two). */
    internal fun named(screens: List<Monitor>): List<Monitor> {
        val sorted = screens.sortedWith(compareBy({ it.x }, { it.y }))
        val places: List<String>? = when {
            !sorted.all { it.rectKnown } || sorted.size < 2 -> null
            sorted.size == 2 -> {
                val (a, b) = sorted
                if (b.x >= a.x + a.width) ["left", "right"]
                else if (a.y + a.height <= b.y) ["top", "bottom"]
                else if (b.y + b.height <= a.y) ["bottom", "top"]
                else null
            }

            sorted.size == 3 && sorted.zipWithNext().all { (a, b) -> b.x >= a.x + a.width } -> ["left", "middle", "right"]
            else -> null
        }
        val main = sorted.singleOrNull { it.rectKnown && it.x == 0 && it.y == 0 }?.takeIf { sorted.size > 1 }
        return sorted.mapIndexed { i, m ->
            val tags = listOfNotNull(places?.get(i), if (m === main) "main" else null)
            m.copy(name = "Screen ${i + 1}" + if (tags.isEmpty()) "" else tags.joinToString(", ", " (", ")"))
        }
    }

    @Volatile
    private var probedScreens: Pair<String, List<Monitor>>? = null

    /** Screens found by [probeWindowsScreens] for [ffmpeg], or null before a probe finished. */
    fun knownWindowsScreens(ffmpeg: String?): List<Monitor>? = probedScreens?.takeIf { it.first == ffmpeg }?.second

    /**
     * Fallback when DXGI cannot be read here: finds the screens by asking `FFmpeg` for each adapter's outputs in turn,
     * until it reports no such adapter (it then silently falls back to the default one). Slow; off the render thread.
     */
    fun probeWindowsScreens(ffmpeg: String): List<Monitor> {
        knownWindowsScreens(ffmpeg)?.let { return it }
        val found = ArrayList<Monitor>()
        var adapter = 0
        var silent = 0
        adapters@ while (silent < 2) {
            var output = 0
            while (true) {
                val m = Monitor(output, "", 0, 0, 0, 0, adapter, rectKnown = false)
                val r = probeScreen(ffmpeg, m)
                if (r == null) {
                    silent++; break
                }
                silent = 0
                if (r.pciId == null) break@adapters // No such adapter
                if (r.size == null) break
                found += m.copy(width = r.size.first, height = r.size.second, adapterPciId = r.pciId,
                    vendorId = r.pciId.substringBefore(':').toIntOrNull(16) ?: 0)
                output++
            }
            adapter++
        }
        val named = found.mapIndexed { i, m -> m.copy(name = "Screen ${i + 1}") }
        logger.info("Screen share: {} screen(s) found by probing: {}.", named.size, named.joinToString { "${it.adapter}:${it.index} ${it.width}x${it.height}" })
        probedScreens = ffmpeg to named
        return named
    }

    private class ScreenProbe(val pciId: String?, val size: Pair<Int, Int>?)

    private val OPENED = Regex("""Opened dxgi output \d+ with dimensions (\d+)x(\d+)""")

    /** Opens [monitor] with Desktop Duplication for one frame; null when `FFmpeg` did not answer in time. */
    private fun probeScreen(ffmpeg: String, monitor: Monitor): ScreenProbe? {
        val args: List<String> = listOf(ffmpeg, "-hide_banner", "-nostats", "-loglevel", "verbose") + adapterArgs(monitor) +
                listOf("-filter_complex", "ddagrab=output_idx=${monitor.index}:framerate=5[v]", "-map", "[v]",
                    "-frames:v", "1", "-f", "null", "-")
        val r = exec(args, timeoutSeconds = 8)
        if (r.tail.startsWith("timed out") || r.output.isEmpty()) return null
        val pci = USING_DEVICE.find(r.output)?.let { "${it.groupValues[1]}:${it.groupValues[2]}".lowercase() }
        val size = if (r.ok) OPENED.find(r.output)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } else null
        return ScreenProbe(pci, size)
    }

    /** Short label for [monitor] in the share picker. */
    fun label(monitor: Monitor): String {
        if (monitor.adapter >= 0 || monitor.deviceName.isNotEmpty()) {
            return String.format(Locale.ROOT, "%s %dx%d", monitor.name, monitor.width, monitor.height)
        }
        val name = monitor.name.ifBlank { "Monitor" }.let { if (it.length > 16) it.take(15) + "…" else it }
        return String.format(Locale.ROOT, "%d: %s %dx%d", monitor.index + 1, name, monitor.width, monitor.height)
    }
}
