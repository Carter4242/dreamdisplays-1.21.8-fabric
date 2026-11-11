package com.dreamdisplays.core.diagnostics

import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Append-only JSON-lines file for client error reports, hitches and display commands (`client-errors.log`). Rotates
 * once the file would grow past [maxBytes]: the current file becomes `<name>.1` (replacing an older one), so at most
 * two files exist.
 */
class ClientErrorLog(val path: Path, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    /** Appends [line] (one JSON object, no newline); returns false when the write failed. */
    @Synchronized
    fun append(line: String): Boolean = try {
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        path.parent?.let(Files::createDirectories)
        val size = if (Files.exists(path)) Files.size(path) else 0L
        if (size > 0L && size + bytes.size > maxBytes) rotate()
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        true
    } catch (_: IOException) {
        false
    }

    /** The rotated-out previous file. */
    val previous: Path get() = path.resolveSibling(path.fileName.toString() + ".1")

    private fun rotate() {
        Files.move(path, previous, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 5L * 1024 * 1024
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

        /** ISO-8601 time with the server's offset (e.g. `2026-11-11T04:12:03.123-08:00`). */
        fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).format(TIME_FORMAT)

        /**
         * One log line for [report] from [playerName] / [playerId]. [build] is the client build id when known;
         * [env] is included the first time the player reports something this session. Fields are expected to be
         * [ReportLimits.sanitize]d already.
         */
        fun reportLine(
            receivedMs: Long,
            playerName: String,
            playerId: UUID,
            build: String?,
            modVersion: String?,
            report: ClientErrorReport,
            displayName: String?,
            env: ClientEnvironment?,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String = buildJsonObject {
            put("type", if (report.isHitch) ClientErrorReport.TYPE_HITCH else ClientErrorReport.TYPE_ERROR)
            put("time", time(receivedMs, zone))
            put("player", playerName)
            put("uuid", playerId.toString())
            if (!build.isNullOrEmpty()) put("build", build)
            if (!modVersion.isNullOrEmpty()) put("version", modVersion)
            put("report", reportJson(report, displayName, zone))
            if (env != null) put("env", envJson(env))
        }.toString()

        /** One log line for a display command [action] by a player (pause, resume, seek, video, mode ...). */
        fun commandLine(
            atMs: Long,
            playerName: String,
            playerId: UUID,
            displayId: UUID,
            displayName: String?,
            action: String,
            positionMs: Long?,
            details: Map<String, String> = emptyMap(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String = buildJsonObject {
            put("type", "command")
            put("time", time(atMs, zone))
            put("player", playerName)
            put("uuid", playerId.toString())
            put("display", displayId.toString())
            if (!displayName.isNullOrEmpty()) put("displayName", displayName)
            put("action", action)
            if (positionMs != null) put("positionMs", positionMs)
            details.forEach { (k, v) -> put(k, v) }
        }.toString()

        fun reportJson(report: ClientErrorReport, displayName: String?, zone: ZoneId = ZoneId.systemDefault()): JsonObject =
            buildJsonObject {
                put("display", report.id.toString())
                if (!displayName.isNullOrEmpty()) put("displayName", displayName)
                put("url", report.url)
                put("stage", report.stage)
                put("kind", report.kind)
                put("message", report.message)
                if (report.httpStatus != 0) put("httpStatus", report.httpStatus)
                if (report.isHitch) {
                    put("durationMs", report.durationMs)
                    put("positionMs", report.positionMs)
                    if (report.action.isNotEmpty()) put("action", report.action)
                } else {
                    put("retry", report.retry)
                    put("maxRetries", report.maxRetries)
                    put("final", report.final)
                    put("fatal", report.fatal)
                    if (report.positionMs > 0) put("positionMs", report.positionMs)
                }
                if (report.attempts.isNotEmpty()) {
                    put("attempts", buildJsonArray { report.attempts.forEach { add(attemptJson(it)) } })
                }
                if (report.stderr.isNotEmpty()) put("stderr", report.stderr)
                if (report.suppressed > 0) put("suppressed", report.suppressed)
                if (report.timestampMs > 0) put("clientTime", time(report.timestampMs, zone))
            }

        private fun attemptJson(a: ResolveAttemptReport): JsonObject = buildJsonObject {
            put("source", a.source)
            if (a.client.isNotEmpty()) put("client", a.client)
            put("ok", a.ok)
            if (a.error.isNotEmpty()) put("error", a.error)
            if (a.elapsedMs > 0) put("ms", a.elapsedMs)
            if (a.agoMs > 0) put("agoMs", a.agoMs)
        }

        fun envJson(e: ClientEnvironment): JsonObject = buildJsonObject {
            putIf("os", listOf(e.osName, e.osVersion, e.osArch).filter { it.isNotEmpty() }.joinToString(" "))
            putIf("java", listOf(e.javaVersion, e.javaVendor).filter { it.isNotEmpty() }.joinToString(" "))
            putIf("gpuVendor", e.gpuVendor)
            putIf("gpuRenderer", e.gpuRenderer)
            putIf("gl", e.glVersion)
            putIf("ytDlpMode", e.ytDlpMode)
            putIf("ytDlpVersion", e.ytDlpVersion)
            putIf("ytDlpPath", e.ytDlpPath)
            putIf("python", e.ytDlpPython)
            putIf("pythonVersion", e.ytDlpPythonVersion)
            putIf("ytDlpNote", e.ytDlpNote)
            putIf("ffmpegSource", e.ffmpegSource)
            putIf("ffmpegVersion", e.ffmpegVersion)
            putIf("ffmpegPath", e.ffmpegPath)
            put("ffmpegDecoders", buildJsonArray { e.ffmpegDecoders.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("ffmpegFilters", buildJsonArray { e.ffmpegFilters.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            putIf("hwAccel", e.hwAccel)
            put("native", e.nativeLoaded)
            putIf("nativeUnavailable", e.nativeUnavailableReason)
            put("lav", e.lavLoaded)
            putIf("lavUnavailable", e.lavUnavailableReason)
            putIf("audioLine", e.audioLine)
            putIf("shaderPack", e.shaderPack)
        }

        private fun JsonObjectBuilder.putIf(key: String, value: String) {
            if (value.isNotEmpty()) put(key, value)
        }
    }
}
