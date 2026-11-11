@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/**
 * Server-wide client policy: the server's [com.dreamdisplays.core.display.policy.DisplayPolicy] JSON carrying its
 * `client` and `debug` sections. Per-display sections keep travelling in each display's custom data.
 *
 * Re-sent whenever it changes (and now and then). A client that received one knows the server understands
 * [ViewerReport]s; clients that do not know this packet type drop it.
 */
@Serializable
data class ServerPolicy(
    @ProtoNumber(1) val policy: ByteArray = ByteArray(0),
) : DreamPacket {
    override fun equals(other: Any?): Boolean = other is ServerPolicy && policy.contentEquals(other.policy)

    override fun hashCode(): Int = policy.contentHashCode()
}

/**
 * How one display is doing for this viewer, sent every few seconds per loaded display (and at once when it fails or
 * recovers), only to servers that sent a [ServerPolicy]. Servers that do not know this packet type drop it.
 */
@Serializable
data class ViewerReport(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    /** [ViewerState] wire value. */
    @ProtoNumber(2) val state: Int = 0,
    /** True while this display follows the server timeline, i.e. the sync fields mean something. */
    @ProtoNumber(3) val syncing: Boolean = false,
    /** Smoothed drift from the server timeline in ms; positive = behind the server. */
    @ProtoNumber(4) val driftMs: Int = 0,
    /** Playback rate the drift correction currently applies (1.0 = none). */
    @ProtoNumber(5) val rate: Double = 1.0,
    /** One-way latency compensation added to the server position, in ms. */
    @ProtoNumber(6) val latencyCompMs: Int = 0,
    /** Strongest correction since the last report: 0 none, 1 rate, 2 snap, 3 seek. */
    @ProtoNumber(7) val action: Int = 0,
    /** Corrective seeks (and paused snaps) since the last report. */
    @ProtoNumber(8) val seeks: Int = 0,
    /** Times playback stopped to buffer since the last report. */
    @ProtoNumber(9) val stalls: Int = 0,
    /** True while playing but waiting for media (buffering / restarting). */
    @ProtoNumber(10) val buffering: Boolean = false,
    @ProtoNumber(11) val positionMs: Long = 0,
    /** Video height being played, 0 = automatic / unknown. */
    @ProtoNumber(12) val qualityHeight: Int = 0,
    /** Failure kind (e.g. `NETWORK`) while the display shows an error, else empty. */
    @ProtoNumber(13) val errorKind: String = "",
    @ProtoNumber(14) val errorMessage: String = "",
) : DreamPacket

/** Coarse display state in a [ViewerReport]. */
enum class ViewerState(val wire: Int, val label: String) {
    UNKNOWN(0, "?"),
    LOADING(1, "loading"),
    PLAYING(2, "playing"),
    PAUSED(3, "paused"),
    BUFFERING(4, "buffering"),
    ERROR(5, "error");

    companion object {
        fun fromWire(wire: Int): ViewerState = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** One resolver step a [ClientErrorReport] lists: which source ran (`NewPipe`, `yt-dlp`, direct ...) and how it went. */
@Serializable
data class ResolveAttemptReport(
    /** Resolver or extractor, e.g. `newpipe`, `yt-dlp zipapp`, `yt-dlp binary`, `direct`, `format-cache`. */
    @ProtoNumber(1) val source: String = "",
    /** `yt-dlp` player client (`visionos`, `android_vr` ...), empty when not applicable. */
    @ProtoNumber(2) val client: String = "",
    @ProtoNumber(3) val ok: Boolean = false,
    /** Short error (one line) when it failed. */
    @ProtoNumber(4) val error: String = "",
    @ProtoNumber(5) val elapsedMs: Int = 0,
    /** How long before the report this step finished, in ms. */
    @ProtoNumber(6) val agoMs: Int = 0,
)

/**
 * A display failed to load, resolve or play for this viewer ([type] `error`: every failed attempt, each automatic
 * retry as well as the final failure), or playback visibly froze ([type] `hitch`: a stall and its restart, a cold
 * restart on resume, a slow seek, a buffering underrun, frames falling behind the audio clock). Deduplicated and
 * rate-limited on the client, only sent to servers that advertise
 * [com.dreamdisplays.shared.capability.ServerFeature.CLIENT_ERROR_REPORTS]. Paths, cookies and stream signatures are
 * scrubbed before sending. Servers that do not know this packet type drop it.
 */
@Serializable
data class ClientErrorReport(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    /** [com.dreamdisplays.api.media.model.MediaFailureKind] name. */
    @ProtoNumber(3) val kind: String = "",
    @ProtoNumber(4) val message: String = "",
    /** [ErrorStage] wire value. */
    @ProtoNumber(5) val stage: String = "",
    /** HTTP status the failure carried (403, 429 ...), 0 when none. */
    @ProtoNumber(6) val httpStatus: Int = 0,
    @ProtoNumber(7) val attempts: List<ResolveAttemptReport> = [],
    /** Tail of the relevant `FFmpeg` / `yt-dlp` stderr (at most 15 lines, 2 KB). */
    @ProtoNumber(8) val stderr: String = "",
    /** Retry number this failure ended (0 = first try). */
    @ProtoNumber(9) val retry: Int = 0,
    @ProtoNumber(10) val maxRetries: Int = 0,
    /** True when no automatic retry follows: the display now shows the error. */
    @ProtoNumber(11) val final: Boolean = false,
    @ProtoNumber(12) val fatal: Boolean = false,
    /** Client wall clock, unix ms. */
    @ProtoNumber(13) val timestampMs: Long = 0,
    /** Identical reports the client dropped since it last sent this one. */
    @ProtoNumber(14) val suppressed: Int = 0,
    /** `error` or `hitch` (empty = `error`). */
    @ProtoNumber(15) val type: String = "",
    /** Hitch: how long the picture was frozen (or, for `av_sync`, the worst lateness), in ms. */
    @ProtoNumber(16) val durationMs: Int = 0,
    /** Playback position when it happened, in ms. */
    @ProtoNumber(17) val positionMs: Long = 0,
    /** What the player did about it: `restart`, `re_resolve`, `retry`, `cold_restart`, `in_place_seek` ... */
    @ProtoNumber(18) val action: String = "",
) : DreamPacket {
    val isHitch: Boolean get() = type == TYPE_HITCH

    companion object {
        const val TYPE_ERROR = "error"
        const val TYPE_HITCH = "hitch"
    }
}

/** Where a playback failure (or hitch) happened, for a [ClientErrorReport]. */
enum class ErrorStage(val wire: String) {
    RESOLVE("resolve"),
    OPEN_STREAM("open_stream"),
    DECODE("decode"),
    AUDIO("audio"),
    HTTP_STATUS("http_status"),
    STALL("stall"),

    // Hitch causes
    SEEK("seek"),
    RESUME("resume"),
    UNDERRUN("underrun"),
    AV_SYNC("av_sync");

    companion object {
        fun fromWire(wire: String): ErrorStage? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The client's media environment, sent once per session (again only if it changed, e.g. once `yt-dlp` was probed),
 * to servers that advertise [com.dreamdisplays.shared.capability.ServerFeature.CLIENT_ERROR_REPORTS]. Paths are
 * scrubbed; nothing identifying (user names, hardware ids) is included.
 */
@Serializable
data class ClientEnvironment(
    @ProtoNumber(1) val osName: String = "",
    @ProtoNumber(2) val osVersion: String = "",
    @ProtoNumber(3) val osArch: String = "",
    @ProtoNumber(4) val javaVersion: String = "",
    @ProtoNumber(5) val javaVendor: String = "",
    @ProtoNumber(6) val gpuVendor: String = "",
    @ProtoNumber(7) val gpuRenderer: String = "",
    @ProtoNumber(8) val glVersion: String = "",
    /** `zipapp`, `binary`, `override`, `unavailable` or `not probed`. */
    @ProtoNumber(9) val ytDlpMode: String = "",
    @ProtoNumber(10) val ytDlpVersion: String = "",
    /** Interpreter running the zipapp (`python3`, `/opt/homebrew/bin/python3`). */
    @ProtoNumber(11) val ytDlpPython: String = "",
    @ProtoNumber(12) val ytDlpPythonVersion: String = "",
    /** Why the zipapp is not used, e.g. interpreters skipped as too old. */
    @ProtoNumber(13) val ytDlpNote: String = "",
    /** `bundled`, `system` or `missing`. */
    @ProtoNumber(14) val ffmpegSource: String = "",
    @ProtoNumber(15) val ffmpegVersion: String = "",
    @ProtoNumber(16) val ffmpegDecoders: List<String> = [],
    @ProtoNumber(17) val ffmpegFilters: List<String> = [],
    @ProtoNumber(18) val hwAccel: String = "",
    @ProtoNumber(19) val nativeLoaded: Boolean = false,
    @ProtoNumber(20) val nativeUnavailableReason: String = "",
    @ProtoNumber(21) val lavLoaded: Boolean = false,
    @ProtoNumber(22) val lavUnavailableReason: String = "",
    /** The display audio line last opened (the `Display audio line` log), empty before any audio played. */
    @ProtoNumber(23) val audioLine: String = "",
    /** Active shader pack backend (`none`, `iris`, `optifine`, `canvas`). */
    @ProtoNumber(24) val shaderPack: String = "",
    @ProtoNumber(25) val ytDlpPath: String = "",
    @ProtoNumber(26) val ffmpegPath: String = "",
) : DreamPacket
