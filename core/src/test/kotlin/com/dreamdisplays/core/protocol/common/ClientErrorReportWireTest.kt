package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import com.dreamdisplays.shared.protocol.PacketDirection
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ClientErrorReportWireTest {
    private val report = ClientErrorReport(
        id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
        url = "https://www.youtube.com/watch?v=abc",
        kind = "NETWORK", message = "YouTube refused the video stream (403).", stage = "http_status", httpStatus = 403,
        attempts = listOf(
            ResolveAttemptReport("newpipe", "", true, "", 812, 9_000),
            ResolveAttemptReport("yt-dlp binary", "visionos", false, "ERROR: Sign in to confirm", 4_100, 3_000),
        ),
        stderr = "[https @ 0x1] HTTP error 403 Forbidden\nError opening input: Server returned 403 Forbidden",
        retry = 3, maxRetries = 3, final = true, fatal = false, timestampMs = 1_762_845_000_000L, suppressed = 2,
        type = "error", positionMs = 12_000L,
    )

    @Test
    fun `report, hitch and environment round trip`() {
        assertEquals(report, PacketRegistry.decode(PacketRegistry.encode(report)))
        val hitch = ClientErrorReport(type = "hitch", stage = "stall", durationMs = 2_300, positionMs = 61_000L, action = "restart")
        assertEquals(hitch, PacketRegistry.decode(PacketRegistry.encode(hitch)))
        val stutter = ClientErrorReport(
            type = "hitch", stage = "stutter", durationMs = 180, cause = "gc",
            metrics = mapOf("gc" to "G1 Young Generation 140ms", "mc_frame_ms" to "150", "pipeline" to "native/d3d11va"),
        )
        assertEquals(stutter, PacketRegistry.decode(PacketRegistry.encode(stutter)))
        val viewer = com.dreamdisplays.core.protocol.common.packets.ViewerReport(
            hitchesLastMinute = 7, hitchTopCause = "decode", worstGapMs = 230,
        )
        assertEquals(viewer, PacketRegistry.decode(PacketRegistry.encode(viewer)))
        assertEquals(ClientErrorReport(), PacketRegistry.decode(PacketRegistry.encode(ClientErrorReport())))
        val env = ClientEnvironment(
            osName = "Windows 11", osArch = "amd64", gpuRenderer = "NVIDIA GeForce RTX 3060/PCIe/SSE2", ytDlpMode = "zipapp",
            ytDlpPython = "python", ytDlpPythonVersion = "3.12.4", ffmpegDecoders = listOf("libvpx-vp9"), nativeLoaded = true,
            audioLine = "Java Sound on \"Speakers\", 48000 Hz", shaderPack = "iris",
            ytDlpJsRuntime = "deno 2.9.8, ~/libs/deno/deno.exe", ytDlpJsRuntimeSource = "downloaded",
        )
        assertEquals(env, PacketRegistry.decode(PacketRegistry.encode(env)))
    }

    @Test
    fun `both travel client to server only`() {
        assertIs<ClientErrorReport>(PacketRegistry.decode(PacketRegistry.encode(report), PacketDirection.CLIENT_TO_SERVER))
        assertIs<ClientEnvironment>(PacketRegistry.decode(PacketRegistry.encode(ClientEnvironment()), PacketDirection.CLIENT_TO_SERVER))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(report), PacketDirection.SERVER_TO_CLIENT)
        }
    }

    @Test
    fun `type ids stay put`() {
        assertEquals(60, PacketType.CLIENT_ERROR_REPORT.id)
        assertEquals(61, PacketType.CLIENT_ENVIRONMENT.id)
    }
}
