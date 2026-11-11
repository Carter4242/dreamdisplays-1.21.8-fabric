package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.core.diagnostics.ReportScrubber
import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.runtime.diagnostics.PlaybackDiagnostics
import com.dreamdisplays.media.source.youtube.binary.YtDlpBinary
import com.dreamdisplays.media.source.youtube.binary.YtDlpJsRuntime
import com.dreamdisplays.platform.client.render.ShaderPackCompat
import org.lwjgl.opengl.GL11

/**
 * Builds the [ClientEnvironment] summary for error reports: OS, Java, GPU strings, how `yt-dlp` (and its JavaScript
 * runtime) and `FFmpeg` run, hardware decoding, the native pipelines, the display audio line and the shader pack.
 * Paths are scrubbed (home directory -> `~`) and owners' names are taken out of device names ([DeviceNames]); no user
 * names or hardware ids are included.
 */
object ClientEnvironmentProbe {
    /** GL strings and shader state, read on the render thread (the only one with a GL context). */
    data class RenderFacts(val gpuVendor: String, val gpuRenderer: String, val glVersion: String, val shaderPack: String)

    @Volatile
    private var gl: Triple<String, String, String>? = null

    /** Reads the render facts; call on the render / client thread. */
    fun renderFacts(): RenderFacts {
        val strings = gl ?: runCatching {
            Triple(
                GL11.glGetString(GL11.GL_VENDOR).orEmpty(),
                GL11.glGetString(GL11.GL_RENDERER).orEmpty(),
                GL11.glGetString(GL11.GL_VERSION).orEmpty(),
            )
        }.getOrNull()?.also { gl = it } ?: Triple("", "", "")
        val shader = runCatching { ShaderPackCompat.shaderBackend().wire }.getOrDefault("unknown")
        return RenderFacts(strings.first, strings.second, strings.third, shader)
    }

    /** The full summary; spawns `yt-dlp --version` / `ffmpeg -version` once, so call it off the client thread. */
    fun build(render: RenderFacts): ClientEnvironment {
        val homes = homes()
        fun s(text: String) = ReportScrubber.scrub(text, homes).take(256)
        val yt = runCatching { YtDlpBinary.describe() }.getOrNull()
        val ff = runCatching { FFmpegBinary.describe() }.getOrNull()
        val js = runCatching { YtDlpJsRuntime.describe() }.getOrNull()
        val probed = runCatching { NativeMedia.probesFinished }.getOrDefault(false)
        val native = probed && runCatching { NativeMedia.isAvailable }.getOrDefault(false)
        val lav = probed && runCatching { NativeMedia.lavAvailable }.getOrDefault(false)
        return ClientEnvironment(
            osName = System.getProperty("os.name").orEmpty(),
            osVersion = System.getProperty("os.version").orEmpty(),
            osArch = System.getProperty("os.arch").orEmpty(),
            javaVersion = System.getProperty("java.runtime.version") ?: System.getProperty("java.version").orEmpty(),
            javaVendor = System.getProperty("java.vendor").orEmpty(),
            gpuVendor = render.gpuVendor.take(128),
            gpuRenderer = render.gpuRenderer.take(256),
            glVersion = render.glVersion.take(128),
            ytDlpMode = yt?.mode ?: "unavailable",
            ytDlpVersion = s(yt?.version.orEmpty()),
            ytDlpPath = s(yt?.path.orEmpty()),
            ytDlpPython = s(yt?.python.orEmpty()),
            ytDlpPythonVersion = yt?.pythonVersion.orEmpty(),
            ytDlpNote = s(yt?.note.orEmpty()),
            ytDlpJsRuntime = s(js?.first.orEmpty()),
            ytDlpJsRuntimeSource = s(js?.second ?: "unavailable"),
            ffmpegSource = ff?.source ?: "unavailable",
            ffmpegVersion = s(ff?.version.orEmpty()),
            ffmpegPath = s(ff?.path.orEmpty()),
            ffmpegDecoders = ff?.decoders.orEmpty(),
            ffmpegFilters = ff?.filters.orEmpty(),
            hwAccel = runCatching { HwAccelBackend.detectDefault().name.lowercase() }.getOrDefault(""),
            nativeLoaded = native,
            nativeUnavailableReason = when {
                native -> ""
                !probed -> "probing"
                else -> s(runCatching { NativeMedia.unavailableReason }.getOrDefault("").ifBlank { "unknown" })
            },
            lavLoaded = lav,
            lavUnavailableReason = when {
                lav -> ""
                !probed -> "probing"
                else -> s(runCatching { NativeMedia.lavUnavailableReason }.getOrDefault("").ifBlank { "unknown" })
            },
            audioLine = s(DeviceNames.anonymize(PlaybackDiagnostics.audioLine)),
            shaderPack = render.shaderPack,
        )
    }

    /** This user's home directory (and its canonical form), scrubbed out of everything reported. */
    fun homes(): List<String> = listOfNotNull(
        System.getProperty("user.home")?.takeIf { it.length >= 3 },
        runCatching { java.io.File(System.getProperty("user.home")).canonicalPath }.getOrNull()?.takeIf { it.length >= 3 },
    ).distinct()
}
