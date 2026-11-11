package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.shared.policy.MediaHosts
import com.dreamdisplays.media.player.pipeline.VideoFramePipe
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import kotlinx.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Builds `FFmpeg` process invocations for the media pipeline and handles their
 * graceful shutdown.
 */
object MediaProcess {
    internal const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


    /** Frame rate assumed when the source reports none, or reports something implausible. */
    const val DEFAULT_OUTPUT_FPS = 30.0

    /** Sanitizes source frame rate to constant pipeline rate. Raw pipes carry no frame-rate metadata. */
    fun outputFps(sourceFps: Double?): Double =
        sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: DEFAULT_OUTPUT_FPS

    /** Most pictures per real second a sped-up session decodes into frames (see [speedFps]). */
    const val MAX_SPEED_FPS = 60.0

    /**
     * The content frame rate a session playing at [tempo] x keeps: [fps] while that shows at most [MAX_SPEED_FPS]
     * pictures a second, otherwise fewer, so a fast session drops frames in FFmpeg (before they are scaled,
     * converted and uploaded) instead of falling behind.
     */
    fun speedFps(fps: Double, tempo: Double): Double =
        if (tempo <= 1.0 || fps * tempo <= MAX_SPEED_FPS) fps else MAX_SPEED_FPS / tempo

    /**
     * Pitch-preserving tempo filter for [tempo] x, or null at normal speed. `atempo` (WSOLA, as browsers use for
     * playback speed) only blends cleanly between 0.5 and 2 per instance, so other speeds are a chain of
     * instances inside that range whose product is [tempo]. Never a resample: the pitch stays put.
     */
    fun tempoFilter(tempo: Double): String? {
        if (!tempo.isFinite() || tempo <= 0.0 || tempo == 1.0) return null
        val stages = ArrayList<Double>()
        var rest = tempo
        while (rest > 2.0) { stages += 2.0; rest /= 2.0 }
        while (rest < 0.5) { stages += 0.5; rest /= 0.5 }
        if (kotlin.math.abs(rest - 1.0) > 1e-9) stages += rest
        return stages.joinToString(",") { "atempo=" + String.format(Locale.US, "%.6f", it) }
    }

    /**
     * Output half of an audio session: s16le stereo at [sampleRate], time-stretched to [tempo] x. A [liveEdge] session
     * flushes every packet into the pipe, so no PCM waits in `FFmpeg`'s output buffer.
     */
    fun audioOutputArgs(sampleRate: Int, tempo: Double = 1.0, liveEdge: Boolean = false): List<String> {
        val filter = tempoFilter(tempo)
        return buildList {
            add("-vn")
            if (filter != null) addAll(["-af", filter])
            if (liveEdge) addAll(["-flush_packets", "1"])
            addAll(["-f", "s16le", "-ar", sampleRate.toString(), "-ac", "2", "-"])
        }
    }

    /** True when [stderr] is `FFmpeg` reporting that the input simply has no audio track, rather than any kind of real failure. */
    fun indicatesNoAudioStream(stderr: String): Boolean {
        return stderr.contains("does not contain any stream") && NO_AUDIO_DISQUALIFIERS.none { stderr.contains(it, ignoreCase = true) }
    }

    private val NO_AUDIO_DISQUALIFIERS = [
        "Server returned", "Connection refused", "Connection timed out", "Invalid data found",
        "No such file or directory", "Protocol not found", "Immediate exit requested",
        "Error opening input", "Unknown error", "I/O error", "HTTP error",
    ]

    private fun headerArgs(url: String): List<String> {
        val referer = MediaHosts.refererFor(url)?.let { "Referer: $it\r\n" }.orEmpty()
        return ["-headers", "User-Agent: $USER_AGENT\r\n$referer"]
    }

    /** Wire format the video `FFmpeg` process writes to its stdout pipe. */
    enum class VideoTransport {
        /** PPM frames (header + RGB24), parsed by the JVM [VideoFramePipe]. */
        PPM,

        /** Headerless NV12 rawvideo for the native pipeline, pinned to BT.709 so it can convert with a fixed matrix. */
        RAW_NV12,

        /**
         * Headerless straight-alpha RGBA rawvideo, parsed by the JVM [VideoFramePipe]. Only for sources with an alpha
         * channel (see [AlphaVideo]); the letterbox bars are transparent.
         */
        RAW_RGBA,
    }

    /** Builds the FFmpeg process for an alpha source: RGBA frames, decoded by [decoder] (null = FFmpeg's default). */
    @Throws(IOException::class)
    fun buildAlphaVideo(
        ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, decoder: String?, fps: Double,
        seekByDecoding: Boolean = false,
    ): Process = ProcessBuilder(alphaVideoArgs(ffmpeg, url, w, h, offsetNanos, decoder, fps, seekByDecoding)).start()

    /**
     * Full FFmpeg argv for an alpha source. Software decode only: FFmpeg's own VP8 / VP9 decoders (and every hardware
     * one) drop the alpha side channel, so [decoder] forces libvpx for those, as an input option.
     */
    internal fun alphaVideoArgs(
        ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, decoder: String?, fps: Double,
        seekByDecoding: Boolean = false,
    ): List<String> {
        val fit = "min($w/iw\\,$h/ih)"
        val vf = "scale=w=min($w\\,iw*$fit):h=min($h\\,ih*$fit):flags=bilinear," +
                "pad=w=$w:h=$h:x=(ow-iw)/2:y=(oh-ih)/2:color=black@0,setsar=1,format=rgba"
        return baseCommand(
            ffmpeg, url, offsetNanos, HwAccelBackend.NONE, alreadyResolved = true, seekByDecoding = seekByDecoding,
            decoder = decoder, picture = true,
        ).apply {
            addAll(["-an", "-vf", vf])
            addAll(["-r", String.format(Locale.US, "%.6f", outputFps(fps))])
            addAll(["-f", "rawvideo", "-"])
        }
    }

    /** Builds FFmpeg process to read video frames from [url] at offset, scaled and cropped to [w]x[h]. */
    @Throws(IOException::class)
    fun buildVideo(
        ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, hwAccel: HwAccelBackend, fps: Double,
        alreadyResolved: Boolean = false, seekByDecoding: Boolean = false, dropToFps: Boolean = false,
    ): Process =
        ProcessBuilder(
            videoArgs(
                ffmpeg, url, w, h, offsetNanos, hwAccel, VideoTransport.PPM, fps, alreadyResolved, seekByDecoding,
                dropToFps,
            ),
        ).start()

    /** Builds full FFmpeg argv for video session emitting [transport] on stdout. Used by native pipeline directly. */
    fun videoArgs(
        ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, hwAccel: HwAccelBackend,
        transport: VideoTransport, fps: Double, alreadyResolved: Boolean = false,
        seekByDecoding: Boolean = false, dropToFps: Boolean = false, liveStampsPath: String? = null,
    ): List<String> {
        val nv12 = transport == VideoTransport.RAW_NV12
        val outFormat = if (nv12) "nv12" else "rgb24"
        val pad = "pad=w=$w:h=$h:x=(ow-iw)/2:y=(oh-ih)/2:color=black,setsar=1,format=$outFormat"
        val vf = if (useHwScale(ffmpeg, hwAccel)) {
            // Scale on the GPU's video block before hwdownload so the CPU never touches
            // full-resolution frames.
            val fit = "min($w/iw\\,$h/ih)"
            val matrix = if (nv12) ":color_matrix=bt709" else ""
            "scale_vt=w=trunc($fit*iw/2)*2:h=trunc($fit*ih/2)*2$matrix,hwdownload,format=nv12,$pad"
        } else {
            val swPrefix = if (hwAccel.hwOutputFormat != null) "hwdownload,format=nv12," else ""
            val scaleExtra = if (nv12) ":out_color_matrix=bt709" else ""
            val fitSw = "min($w/iw\\,$h/ih)"
            "${swPrefix}scale=w=min($w\\,iw*$fitSw):h=min($h\\,ih*$fitSw):flags=bilinear$scaleExtra,$pad"
        }
        // A sped-up session thins frames out first, so the dropped ones are never scaled or converted
        val filters = if (dropToFps) "fps=${String.format(Locale.US, "%.6f", outputFps(fps))},$vf" else vf
        val liveEdge = LiveStreamUrls.isLive(url)
        return baseCommand(ffmpeg, url, offsetNanos, hwAccel, alreadyResolved, seekByDecoding, picture = true).apply {
            addAll(["-an", "-vf", filters])
            if (liveEdge) {
                // Every decoded picture goes out the moment it exists: no constant-rate resampling (which holds a
                // frame back to decide whether to duplicate or drop it), and no output buffering
                addAll(["-fps_mode", "passthrough", "-flush_packets", "1"])
                // Each frame's stream timestamp, for share latency (see LiveFrameStamps)
                if (liveStampsPath != null) addAll(com.dreamdisplays.media.player.pipeline.LiveFrameStamps.ffmpegArgs(liveStampsPath))
            } else {
                addAll(["-r", String.format(Locale.US, "%.6f", outputFps(fps))])
            }
            addAll(if (nv12) ["-f", "rawvideo", "-"] else ["-f", "image2pipe", "-c:v", "ppm", "-"])
        }
    }

    private fun useHwScale(ffmpeg: String, hwAccel: HwAccelBackend): Boolean =
        hwAccel == HwAccelBackend.VIDEOTOOLBOX
                && FFmpegCapabilities.hasFilter(ffmpeg, "scale_vt")

    /** Builds FFmpeg process that seeks to offset in URL and writes a single letterboxed JPEG frame. */
    @Throws(IOException::class)
    fun buildFrameExtract(
        ffmpeg: String, url: String, offsetNanos: Long, w: Int, h: Int, seekByDecoding: Boolean = false,
    ): Process {
        val fitSw = "min($w/iw\\,$h/ih)"
        val pad = "scale=w=min($w\\,iw*$fitSw):h=min($h\\,ih*$fitSw):flags=lanczos," +
                "pad=w=$w:h=$h:x=(ow-iw)/2:y=(oh-ih)/2:color=black"
        val cmd = baseCommand(
            ffmpeg, url, offsetNanos, HwAccelBackend.NONE,
            alreadyResolved = true, seekByDecoding = seekByDecoding, picture = true,
        ).apply {
            addAll(
                [
                    "-an", "-frames:v", "1",
                    "-threads", "1",
                    "-vf", pad, "-q:v", "3",
                    "-f", "image2pipe", "-vcodec", "mjpeg", "-",
                ]
            )
        }
        return ProcessBuilder(cmd).start()
    }

    /**
     * Builds an `FFmpeg` process to read audio samples from [url] at the given [offsetNanos], resampled to [sampleRate] Hz
     * and played at [tempo] x (each PCM frame then carries [tempo] frames of content).
     * @throws IOException if the process fails to start. The caller is responsible for destroying the process when done.
     */
    @Throws(IOException::class)
    fun buildAudio(
        ffmpeg: String, url: String, offsetNanos: Long, sampleRate: Int, seekByDecoding: Boolean = false,
        tempo: Double = 1.0,
    ): Process {
        val cmd = baseCommand(
            ffmpeg, url, offsetNanos, HwAccelBackend.NONE, seekByDecoding = seekByDecoding,
        ).apply {
            addAll(audioOutputArgs(sampleRate, tempo, liveEdge = LiveStreamUrls.isLive(url)))
        }
        return ProcessBuilder(cmd).start()
    }

    /** Builds `FFmpeg` process that decodes audio from HLS segments (MPEG-TS or fragmented MP4) piped to stdin. */
    @Throws(IOException::class)
    fun buildAudioPiped(ffmpeg: String, sampleRate: Int): Process {
        val cmd = [
            ffmpeg,
            "-hide_banner", "-loglevel", "error", "-nostats",
            "-probesize", "1M", "-analyzeduration", "1000000",
            "-i", "pipe:0",
            "-vn", "-f", "s16le", "-ar", sampleRate.toString(), "-ac", "2", "-",
        ]
        return ProcessBuilder(cmd).start()
    }

    /**
     * Closes the process's output stream and destroys it. Waits up to 1 second for graceful termination, then forcibly destroys if needed.
     * Safe to call multiple times or from any thread. Does nothing if [proc] is null.
     */
    fun gracefulDestroy(proc: Process?) {
        if (proc == null) return
        runCatching { proc.outputStream?.close() }
        proc.destroy()
        runCatching {
            if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
        }.onFailure { e ->
            if (e !is InterruptedException) throw e
            proc.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    @Throws(IOException::class)
    private fun baseCommand(
        ffmpeg: String,
        url: String,
        offsetNanos: Long,
        hwAccel: HwAccelBackend,
        alreadyResolved: Boolean = false,
        seekByDecoding: Boolean = false,
        decoder: String? = null,
        picture: Boolean = false,
    ): MutableList<String> {
        val safeUrl = if (alreadyResolved) url else MediaHostGuard.resolveSafeUrl(url)
        if (LiveStreamUrls.isLive(safeUrl)) return liveInputCommand(ffmpeg, safeUrl, hwAccel, decoder, picture)
        // This container's demuxer cannot be trusted with a jump, so the best available seek is a
        // playlist that already starts at the target ([HlsSeekPlaylist]).
        val trimmed =
            if (seekByDecoding && offsetNanos > 0) HlsSeekPlaylist.trim(safeUrl, offsetNanos) else null
        val input = if (trimmed == null) GoogleVideoRangeProxy.wrap(safeUrl) else safeUrl
        return inputCommand(ffmpeg, input, offsetNanos, hwAccel, seekByDecoding, trimmed, decoder)
    }

    /** Input half of `FFmpeg` invocation with playlist trim already decided. */
    internal fun inputCommand(
        ffmpeg: String,
        safeUrl: String,
        offsetNanos: Long,
        hwAccel: HwAccelBackend,
        seekByDecoding: Boolean,
        trimmed: HlsSeekPlaylist.Trimmed?,
        decoder: String? = null,
    ): MutableList<String> {
        return mutableListOf<String>().apply {
            add(ffmpeg)
            addAll(["-hide_banner", "-loglevel", "error", "-nostats"])
            // TODO: httpproxy is sus
            addAll(["-protocol_whitelist", "https,tls,tcp,crypto,data,http,httpproxy"])
            // https://github.com/arnodoelinger/dreamdisplays/issues/218
            // TODO: i should rewrite some parts of code to fix tls issue properly but i'm too lazy
            // https://github.com/arnodoelinger/dreamdisplays/issues/209
            if (trimmed == null && safeUrl.startsWith("https:", ignoreCase = true)) {
                addAll(["-tls_verify", "0"])
            }
            if (hwAccel.ffmpegName != null) {
                addAll(["-hwaccel", hwAccel.ffmpegName])
            }
            hwAccel.hwOutputFormat?.let { addAll(["-hwaccel_output_format", it]) }
            if (trimmed == null) {
                addHttpOptions(safeUrl)
            } else {
                // Every option below belongs to the http protocol, and the input here is a data:
                // URI, so none of them has a context to be applied to: FFmpeg reports the first one
                // as "Option headers not found" and refuses to open the input at all. The HLS
                // demuxer's own retry counters are protocol-independent and cover the same ground
                // for the segment fetches, which are the requests that actually matter here.
                addAll(["-seg_max_retry", "3", "-max_reload", "3"])
            }
            addAll(["-rw_timeout", "15000000"])
            // The sources here are plain MP4 / WebM / M4A over HTTPS with stream info in their headers;
            // the default 5 MB / 5 s probe window only postpones the first frame, so keep it tight.
            // Deliberately no -fflags nobuffer: it drops the leading GOP on open and on every
            // in-place seek (see native/lav/src/session.rs, which dropped the same flag for the
            // same reason).
            addAll(["-probesize", "1M", "-analyzeduration", "1000000"])
            // A decoder choice is an input option, so it has to come before -i
            if (decoder != null) addAll(["-c:v", decoder])
            when {
                trimmed != null -> {
                    // Forcing the demuxer is required: FFmpeg will not probe a playlist out of a
                    // data: URI ("Not detecting m3u8/hls with non-standard extension").
                    addAll(["-f", "hls", "-i", trimmed.url])
                    if (trimmed.residualNanos > 0) addAll(seekArgs(trimmed.residualNanos))
                }
                // -ss after -i makes FFmpeg decode from the start and discard: always correct,
                // and slower the deeper the seek. Only reached when the playlist could not be read.
                seekByDecoding -> {
                    addAll(["-i", safeUrl])
                    if (offsetNanos > 0) addAll(seekArgs(offsetNanos))
                }
                // -ss before -i asks the demuxer to jump, which is what makes a seek instant
                else -> {
                    if (offsetNanos > 0) addAll(seekArgs(offsetNanos))
                    addAll(["-i", safeUrl])
                }
            }
        }
    }

    /** Protocols a live relay input may use: the relay's own, and what they run over (RTSP's RTP, RTMPS's TLS). */
    internal const val LIVE_PROTOCOLS = "srt,rtmp,rtmps,rtsp,rtp,tcp,udp,tls"

    /** How long a live input may go without data before `FFmpeg` gives up, so a dropped relay reconnects quickly. */
    private const val LIVE_RW_TIMEOUT_MICROS = "2000000"

    /**
     * Input half for a live relay stream (`srt://`, `rtmp://`, `rtmps://`, `rtsp://`), tuned to sit at the live edge: a
     * probe that ends as soon as it can, the low-delay decoder (no frame threading, which holds back a frame per
     * thread), and a short I/O timeout. The URL is expected to carry its SRT latency already ([LiveStreamUrls.playUrl]).
     *
     * The [picture] process keeps what the probe read (the stream starts at a keyframe, and the probe is a fraction of
     * a second, decoded at once); with `nobuffer` it was dropped, and the first picture waited for the next keyframe.
     * The sound process drops it: sound read during the probe would play late for the rest of the session.
     */
    internal fun liveInputCommand(
        ffmpeg: String, url: String, hwAccel: HwAccelBackend, decoder: String? = null, picture: Boolean = false,
    ): MutableList<String> = mutableListOf<String>().apply {
        add(ffmpeg)
        addAll(["-hide_banner", "-loglevel", "error", "-nostats"])
        addAll(["-protocol_whitelist", LIVE_PROTOCOLS])
        if (hwAccel.ffmpegName != null) addAll(["-hwaccel", hwAccel.ffmpegName])
        hwAccel.hwOutputFormat?.let { addAll(["-hwaccel_output_format", it]) }
        addAll(["-fflags", if (picture) "discardcorrupt" else "nobuffer+discardcorrupt", "-flags", "low_delay"])
        addAll(["-probesize", LIVE_PROBE_SIZE, "-analyzeduration", LIVE_ANALYZE_MICROS, "-fpsprobesize", "0", "-max_delay", "0"])
        when (LiveStreamUrls.schemeOf(url)) {
            // RTSP opens its own sockets, so the generic I/O timeout does not exist for it ("Option not found")
            "rtsp" -> addAll(["-rtsp_transport", "tcp", "-reorder_queue_size", "0", "-timeout", LIVE_RW_TIMEOUT_MICROS])
            // The client buffer RTMP announces to the server (3 s by default)
            "rtmp", "rtmps" -> addAll(["-rw_timeout", LIVE_RW_TIMEOUT_MICROS, "-rtmp_live", "live", "-rtmp_buffer", "100"])
            else -> addAll(["-rw_timeout", LIVE_RW_TIMEOUT_MICROS])
        }
        if (decoder != null) addAll(["-c:v", decoder])
        addAll(["-i", url])
    }

    /**
     * How much of a live input `FFmpeg` probes before it decodes: [LIVE_PROBE_SIZE] bytes or [LIVE_ANALYZE_MICROS] of
     * stream, whichever comes first, with no frame-rate estimate (`-fpsprobesize 0`; output timing never uses it).
     *
     * A stream with sound and picture is fully known at its first keyframe and the probe ends there. A picture-only
     * one never is over RTMP: FFmpeg's RTMP client always announces sound too, so the probe runs to a limit. With the
     * old 500 kB and no time limit (`-analyzeduration 0` is FLV's 90 s) that was 4 s at 1 Mbit/s and longer on a still
     * screen, past the live player's 6 s startup watchdog, which then reconnected and started over.
     *
     * The limits must still let a stream with sound see its first keyframe: the relay sends sound at once but
     * pictures only from the next keyframe (a second apart; a quarter second while a share starts), so before it
     * come up to a second of sound (~22 kB at the share's 160 kbit/s AAC). With less, the picture had no stream of its
     * own and failed. 32 kB is 0.26 s of a 1 Mbit/s picture-only share.
     */
    internal const val LIVE_PROBE_SIZE = "32768"

    /** See [LIVE_PROBE_SIZE]: above the share's keyframe interval (1 s) with room to spare. */
    internal const val LIVE_ANALYZE_MICROS = "1500000"

    private fun MutableList<String>.addHttpOptions(url: String) {
        addAll(headerArgs(url))
        addAll(
            [
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "10",
                "-reconnect_on_network_error", "1",
                "-reconnect_on_http_error", "5xx",
            ]
        )
        if (!HlsAudioFeeder.supports(url)) addAll(["-multiple_requests", "1"])
    }

    private fun seekArgs(offsetNanos: Long): List<String> =
        ["-ss", String.format(Locale.US, "%.6f", offsetNanos / 1e9)]
}
