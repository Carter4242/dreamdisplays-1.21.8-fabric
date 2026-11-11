package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Builds and probes the `FFmpeg` screen-capture + encode process behind the built-in "Share screen": one monitor,
 * captured at [Settings.fps], scaled to fit 1080p, encoded for the lowest latency the machine offers (hardware H.264
 * when it works, else x264 `ultrafast` / `zerolatency`) and pushed to a live relay as MPEG-TS over SRT (FLV for RTMP).
 * Video only: `FFmpeg` has no built-in way to capture what the desktop plays.
 */
object ScreenCapture {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Where the picture comes from. */
    enum class Source { DDAGRAB, AVFOUNDATION, X11GRAB }

    /**
     * One monitor. [index] is its position in GLFW's list (the primary first), or for Desktop Duplication the output
     * index on graphics adapter [adapter] (-1: the default adapter).
     */
    data class Monitor(
        val index: Int, val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val adapter: Int = -1,
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
    )

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
    private var probed: Pair<String, Encoder>? = null

    /**
     * The first encoder in [candidates] that really encodes on this machine (a build lists `h264_nvenc` whether or not
     * there is an NVIDIA GPU). Blocks for a few short `FFmpeg` runs the first time; call off the render thread.
     */
    /**
     * True when the server-supplied [publishUrl] is a relay this client may stream the screen to: a well-formed live
     * relay URL (`srt` / `rtmp(s)` / `rtsp`) on a public host. Without it `FFmpeg` takes any output: a local file
     * path, `file:`, `http://` or `tcp://` to a host on the player's LAN. Resolves the host (call off the render thread).
     */
    fun publishTargetAllowed(publishUrl: String): Boolean =
        LiveStreamUrls.isValid(publishUrl) && MediaHostGuard.isAllowed(publishUrl)

    fun pickEncoder(ffmpeg: String): Encoder {
        probed?.takeIf { it.first == ffmpeg }?.let { return it.second }
        val chosen = candidates().firstOrNull { it == Encoder.X264 || works(ffmpeg, it) } ?: Encoder.X264
        logger.info("Screen share encoder: {}.", chosen.ffmpegName)
        probed = ffmpeg to chosen
        return chosen
    }

    private fun works(ffmpeg: String, encoder: Encoder): Boolean = runCatching {
        val args = [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostats",
            "-f", "lavfi", "-i", "color=c=black:s=640x360:r=30", "-frames:v", "3",
            "-pix_fmt", "nv12", "-c:v", encoder.ffmpegName, "-f", "null", "-",
        ]
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly(); return false
        }
        (p.exitValue() == 0).also { ok ->
            if (!ok) logger.debug("Encoder {} unusable here: {}", encoder.ffmpegName, out.trim().take(200))
        }
    }.getOrDefault(false)

    /** Full `FFmpeg` argv capturing [monitor] with [source], encoding with [encoder], publishing to [publishUrl]. */
    fun args(
        ffmpeg: String, source: Source, monitor: Monitor, encoder: Encoder, publishUrl: String,
        settings: Settings = Settings(),
    ): List<String> = buildList {
        add(ffmpeg)
        addAll(["-hide_banner", "-loglevel", "error", "-nostats"])
        val fps = settings.fps.toString()
        val fit = "scale=w='min(${settings.maxWidth},iw)':h='min(${settings.maxHeight},ih)'" +
                ":force_original_aspect_ratio=decrease:force_divisible_by=2:flags=fast_bilinear"
        val pixFmt = if (encoder.hardware) "nv12" else "yuv420p"
        when (source) {
            Source.DDAGRAB -> {
                // Desktop Duplication: the GPU hands over each new desktop frame (repeating the last one while the
                // screen is still, so the stream never pauses); downloaded once, scaled on the CPU. It only sees the
                // screens of one graphics adapter, so a screen on another one names its adapter.
                if (monitor.adapter >= 0) addAll(adapterArgs(monitor.adapter))
                addAll(["-f", "lavfi", "-i", "ddagrab=output_idx=${monitor.index}:framerate=$fps:draw_mouse=1"])
                addAll(["-vf", "hwdownload,format=bgra,$fit,format=$pixFmt"])
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

    private fun adapterArgs(adapter: Int): List<String> =
        ["-init_hw_device", "d3d11va=dd:$adapter", "-filter_hw_device", "dd"]

    @Volatile
    private var windowsScreens: Pair<String, List<Monitor>>? = null

    /** Screens found by [probeWindowsScreens] for [ffmpeg], or null before a probe finished. */
    fun knownWindowsScreens(ffmpeg: String?): List<Monitor>? = windowsScreens?.takeIf { it.first == ffmpeg }?.second

    /**
     * Every screen Desktop Duplication can capture here: each graphics adapter's outputs, in order, with their size.
     * A laptop's internal screen often hangs off another adapter than an external one, which the default adapter
     * cannot reach. A few short `FFmpeg` runs; call off the render thread.
     */
    fun probeWindowsScreens(ffmpeg: String): List<Monitor> {
        knownWindowsScreens(ffmpeg)?.let { return it }
        val found = ArrayList<Monitor>()
        for (adapter in 0 until MAX_ADAPTERS) {
            for (output in 0 until MAX_OUTPUTS) {
                val size = probeScreen(ffmpeg, adapter, output) ?: break
                found += Monitor(output, "Screen ${found.size + 1}", 0, 0, size.first, size.second, adapter)
            }
        }
        logger.info("Screen share: {} screen(s) found: {}.", found.size, found.joinToString { "${it.adapter}:${it.index} ${it.width}x${it.height}" })
        windowsScreens = ffmpeg to found
        return found
    }

    private const val MAX_ADAPTERS = 4
    private const val MAX_OUTPUTS = 4
    private val SIZE = Regex("""Video: [^\r\n]*?[ ,](\d{3,5})x(\d{3,5})[ ,\[]""")

    private fun probeScreen(ffmpeg: String, adapter: Int, output: Int): Pair<Int, Int>? = runCatching {
        val args = listOf(ffmpeg, "-hide_banner", "-nostats") + adapterArgs(adapter) +
                listOf("-f", "lavfi", "-i", "ddagrab=output_idx=$output:framerate=5", "-frames:v", "1", "-f", "null", "-")
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(8, TimeUnit.SECONDS)) {
            p.destroyForcibly(); return null
        }
        if (p.exitValue() != 0) return null
        SIZE.find(out)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }.getOrNull()

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

    /** Short label for [monitor] in the share picker. */
    fun label(monitor: Monitor): String {
        if (monitor.adapter >= 0) {
            return String.format(Locale.ROOT, "%s %dx%d", monitor.name, monitor.width, monitor.height)
        }
        val name = monitor.name.ifBlank { "Monitor" }.let { if (it.length > 16) it.take(15) + "…" else it }
        return String.format(Locale.ROOT, "%d: %s %dx%d", monitor.index + 1, name, monitor.width, monitor.height)
    }
}
