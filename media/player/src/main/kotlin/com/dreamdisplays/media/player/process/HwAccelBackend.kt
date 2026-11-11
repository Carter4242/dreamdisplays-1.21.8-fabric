package com.dreamdisplays.media.player.process

import com.dreamdisplays.util.OsInfo

/**
 * Hardware-accelerated video decoder backends supported by `FFmpeg`.
 *
 * The intent is to offload H.264 / HEVC / VP9 / AV1 decoding from the CPU to the GPU's dedicated
 * video decode block (`NVDEC` on NVIDIA, `VCE` on AMD, `QuickSync` on Intel, the Apple VT block on macOS).
 */
enum class HwAccelBackend(val ffmpegName: String?, val hwOutputFormat: String?, val lavCode: Int) {
    /** Apple platforms. Handles h264, hevc, vp9, prores. */
    VIDEOTOOLBOX("videotoolbox", "videotoolbox_vld", 2),

    /** Windows `Direct3D 11` Video Acceleration. */
    D3D11VA("d3d11va", "d3d11", 3),

    /** Linux `Video Acceleration API` works on AMD / Intel; NVIDIA needs the nouveau or proprietary `VAAPI` bridge. */
    VAAPI("vaapi", "vaapi", 4),

    /** NVIDIA CUDA / NVDEC are fastest on NVIDIA, but limited to NVIDIA cards. */
    CUDA("cuda", "cuda", 5),

    /** Software decoding only. */
    NONE(null, null, 0);

    companion object {
        /**
         * Picks a sensible default backend for the host OS. We deliberately pick the most broadly
         * compatible option per-platform rather than the absolute fastest: a stream that fails to
         * decode is worse than a stream that decodes a bit slower.
         */
        fun detectDefault(): HwAccelBackend = when {
            OsInfo.isMac -> VIDEOTOOLBOX
            OsInfo.isWindows -> D3D11VA
            OsInfo.isLinux -> VAAPI
            else -> NONE
        }

        /**
         * Returns true if [stderr] looks like an `FFmpeg` startup failure caused specifically by the hardware decoder
         * (as opposed to some unrelated error), so we can fall back to software.
         */
        fun looksLikeHwAccelFailure(stderr: String): Boolean {
            if (stderr.isEmpty()) return false
            val s = stderr.lowercase()
            return HWACCEL_FAIL_MARKERS.any { s.contains(it) }
        }

        private val HWACCEL_FAIL_MARKERS = [
            "hwaccel",
            "videotoolbox",
            "d3d11va",
            "vaapi",
            "cuda",
            "cuvid",
            "nvdec",
            "hardware acceleration",
            "failed setup for format",
            "no device available",
            "device creation failed",
            "no usable hwaccel",
            "decoder does not support",
            "scale_vt",
            "no such filter",
        ]

        /** Backends whose device could not even be created here (no driver for this GPU): off for the session. */
        private val noDevice = java.util.concurrent.ConcurrentHashMap.newKeySet<HwAccelBackend>()

        /**
         * True when [stderr] says `FFmpeg` could not create the decoding device at all (`Device creation failed`, no
         * VA display, no CUDA driver), as opposed to a device that exists but refused one stream.
         */
        fun looksLikeNoDevice(stderr: String): Boolean {
            val s = stderr.lowercase()
            return NO_DEVICE_MARKERS.any { s.contains(it) }
        }

        private val NO_DEVICE_MARKERS = [
            "device creation failed",
            "no device available",
            "no va display",
            "failed to initialise vaapi",
            "cannot load libcuda",
        ]

        /**
         * Records that [backend]'s device cannot be created on this machine. Every later stream then starts in
         * software at once: each new player (every screen share is one) used to try the device again, fail after
         * connecting and probing, and only then start over in software.
         */
        fun markNoDevice(backend: HwAccelBackend) {
            if (backend != NONE) noDevice.add(backend)
        }

        /** [backend], or [NONE] when its device is known not to work here (see [markNoDevice]). */
        fun usable(backend: HwAccelBackend): HwAccelBackend = if (backend in noDevice) NONE else backend

        internal fun forgetNoDevice() = noDevice.clear()

        /** Backends a stream reported without a device, being checked by [confirmNoDeviceAsync]. */
        private val confirming = java.util.concurrent.ConcurrentHashMap.newKeySet<HwAccelBackend>()

        /** How long after a stream's report the device is opened on its own: a GPU reset (TDR) takes a few seconds. */
        internal const val CONFIRM_DELAY_MS = 5_000L

        /**
         * A stream reported that [backend] has no device. It goes off for the session ([markNoDevice]) only when opening
         * the device on its own, [CONFIRM_DELAY_MS] later, fails the same way: a GPU reset, a resume from sleep or a
         * driver update fails one stream's device without the device being gone, and that stream already falls back
         * to software on its own. One check per backend at a time, in the background.
         */
        fun confirmNoDeviceAsync(backend: HwAccelBackend, ffmpeg: () -> String?) {
            if (backend.ffmpegName == null || backend in noDevice || !confirming.add(backend)) return
            Thread({
                try {
                    Thread.sleep(CONFIRM_DELAY_MS)
                    ffmpeg()?.let { probeDevice(it, backend) }
                } catch (_: InterruptedException) {
                } finally {
                    confirming.remove(backend)
                }
            }, "HwAccel-device-check").apply { isDaemon = true }.start()
        }

        /** True when a device-only `FFmpeg` run that exited with [exitCode] and printed [output] found no device. */
        internal fun deviceMissing(exitCode: Int, output: String): Boolean = exitCode != 0 && looksLikeNoDevice(output)

        /**
         * Opens [backend]'s device (the default one unless given) once in [ffmpeg] (no stream involved) and records it
         * as unusable when that fails for want of a device ([markNoDevice]). Run in the background at startup:
         * otherwise the first stream of each player found out only after connecting and probing, and started over in
         * software.
         */
        fun probeDevice(ffmpeg: String, backend: HwAccelBackend = detectDefault()) {
            val name = backend.ffmpegName ?: return
            val output = runCatching {
                val p = ProcessBuilder(
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-init_hw_device", "$name=hw",
                    "-f", "lavfi", "-i", "nullsrc=s=64x64:d=0.1", "-frames:v", "1", "-f", "null", "-",
                ).redirectErrorStream(true).start()
                runCatching { p.outputStream.close() }
                val text = StringBuilder()
                val reader = Thread { runCatching { p.inputStream.bufferedReader().use { text.append(it.readText()) } } }
                    .apply { isDaemon = true; start() }
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return
                }
                reader.join(1_000)
                if (!deviceMissing(p.exitValue(), text.toString())) return
                text.toString()
            }.getOrNull() ?: return
            markNoDevice(backend)
            org.slf4j.LoggerFactory.getLogger(HwAccelBackend::class.java)
                .info("Hardware decoding ({}) has no device here; decoding in software: {}", name, output.trim().take(160))
        }
    }
}
