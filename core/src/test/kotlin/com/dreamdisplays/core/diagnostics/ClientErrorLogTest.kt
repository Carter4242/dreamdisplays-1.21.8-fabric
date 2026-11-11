package com.dreamdisplays.core.diagnostics

import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.ZoneId
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientErrorLogTest {
    private val player = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val display = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val zone = ZoneId.of("America/Los_Angeles")

    private val report = ClientErrorReport(
        id = display, url = "https://www.youtube.com/watch?v=abc", kind = "UNKNOWN", message = "All yt-dlp clients failed",
        stage = "resolve", attempts = listOf(ResolveAttemptReport("yt-dlp binary", "visionos", false, "ERROR: x", 900, 1200)),
        stderr = "ERROR: x", retry = 1, maxRetries = 2, final = false, timestampMs = 1_762_845_000_000L, type = "error",
    )

    @Test
    fun `report line is one json object with player, build, report and env`() {
        val env = ClientEnvironment(osName = "Mac OS X", osVersion = "15.1", osArch = "aarch64", ytDlpMode = "binary")
        val line = ClientErrorLog.reportLine(1_762_845_001_234L, "Steve", player, "1a2b3c4d", "1.9.0", report, "lobby", env, zone)
        assertFalse('\n' in line)
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals("error", json["type"]!!.jsonPrimitive.content)
        assertEquals("2025-11-10T23:10:01.234-08:00", json["time"]!!.jsonPrimitive.content)
        assertEquals("Steve", json["player"]!!.jsonPrimitive.content)
        assertEquals(player.toString(), json["uuid"]!!.jsonPrimitive.content)
        assertEquals("1a2b3c4d", json["build"]!!.jsonPrimitive.content)
        val r = json["report"]!!.jsonObject
        assertEquals("resolve", r["stage"]!!.jsonPrimitive.content)
        assertEquals("lobby", r["displayName"]!!.jsonPrimitive.content)
        assertEquals("visionos", r["attempts"]!!.jsonArray[0].jsonObject["client"]!!.jsonPrimitive.content)
        assertEquals("Mac OS X 15.1 aarch64", json["env"]!!.jsonObject["os"]!!.jsonPrimitive.content)
        // Without env (not the first report of the session) there is no env key
        val second = Json.parseToJsonElement(ClientErrorLog.reportLine(0L, "Steve", player, null, null, report, null, null, zone)).jsonObject
        assertFalse("env" in second)
        assertFalse("build" in second)
    }

    @Test
    fun `hitch and command lines`() {
        val hitch = report.copy(type = "hitch", stage = "stall", durationMs = 2300, positionMs = 61_000L, action = "restart", attempts = emptyList())
        val h = Json.parseToJsonElement(ClientErrorLog.reportLine(0L, "Alex", player, null, null, hitch, null, null, zone)).jsonObject
        assertEquals("hitch", h["type"]!!.jsonPrimitive.content)
        assertEquals("2300", h["report"]!!.jsonObject["durationMs"]!!.jsonPrimitive.content)
        assertFalse("retry" in h["report"]!!.jsonObject)
        val stutter = hitch.copy(stage = "stutter", cause = "gc", metrics = mapOf("gc" to "G1 Young 140ms", "gap_ms" to "180"))
        val st = Json.parseToJsonElement(ClientErrorLog.reportLine(0L, "Alex", player, null, null, stutter, null, null, zone)).jsonObject
        assertEquals("gc", st["report"]!!.jsonObject["cause"]!!.jsonPrimitive.content)
        assertEquals("G1 Young 140ms", st["report"]!!.jsonObject["metrics"]!!.jsonObject["gc"]!!.jsonPrimitive.content)
        val c = Json.parseToJsonElement(
            ClientErrorLog.commandLine(0L, "Alex", player, display, "lobby", "pause", 61_000L, mapOf("mode" to "synced"), zone)
        ).jsonObject
        assertEquals("command", c["type"]!!.jsonPrimitive.content)
        assertEquals("pause", c["action"]!!.jsonPrimitive.content)
        assertEquals("61000", c["positionMs"]!!.jsonPrimitive.content)
        assertEquals("synced", c["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rotates past the size cap keeping one old file`() {
        val dir = Files.createTempDirectory("errlog")
        try {
            val log = ClientErrorLog(dir.resolve("client-errors.log"), maxBytes = 1_000)
            repeat(25) { i -> assertTrue(log.append("{\"n\":$i,\"pad\":\"${"x".repeat(80)}\"}")) }
            assertTrue(Files.size(log.path) <= 1_000)
            assertTrue(Files.exists(log.previous))
            assertTrue(Files.size(log.previous) <= 1_000)
            assertEquals(2, Files.list(dir).use { it.count() }.toInt())
            assertTrue(Files.readAllLines(log.path).last().startsWith("{\"n\":24"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `server caps and cleans untrusted reports`() {
        val hostile = report.copy(
            url = "https://x.googlevideo.com/videoplayback?sig=abc&ip=1.2.3.4",
            message = "§cfake\u0007 /Users/bob/x " + "m".repeat(5_000),
            kind = "K".repeat(500),
            attempts = List(100) { ResolveAttemptReport("s", error = "e".repeat(1_000)) },
            stderr = (1..100).joinToString("\n") { "l$it" + "z".repeat(100) },
            httpStatus = 99_999,
            type = "weird",
            durationMs = -5,
        )
        val clean = ReportLimits.sanitize(hostile)
        assertEquals("https://x.googlevideo.com/videoplayback?<redacted>", clean.url)
        assertEquals(ReportLimits.MAX_TEXT, clean.message.length)
        assertFalse('\u0007' in clean.message)
        assertFalse("/Users/bob" in clean.message)
        assertEquals(ReportLimits.MAX_SHORT, clean.kind.length)
        assertEquals(ReportLimits.MAX_ATTEMPTS, clean.attempts.size)
        assertEquals(ReportLimits.MAX_ATTEMPT_ERROR, clean.attempts[0].error.length)
        assertTrue(clean.stderr.toByteArray().size <= ReportLimits.MAX_STDERR_BYTES)
        assertTrue(clean.stderr.lines().size <= ReportLimits.MAX_STDERR_LINES)
        assertEquals(0, clean.httpStatus)
        assertEquals("error", clean.type)
        assertEquals(0, clean.durationMs)
        assertFalse('§' in ReportLimits.forChat(clean.message))
    }

    @Test
    fun `per player rate`() {
        val rate = ReportLimits.Rate(3)
        assertEquals(3, (0 until 10).count { rate.tryAcquire(player, it.toLong()) })
        assertTrue(rate.tryAcquire(player, 60_001L))
    }
}
