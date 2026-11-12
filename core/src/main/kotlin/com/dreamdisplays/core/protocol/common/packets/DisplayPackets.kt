@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType
import java.util.*

/** Full description of a single display. */
@Serializable
data class DisplayInfo(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val ownerId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(3) @ProtoType(ProtoIntegerType.SIGNED) val x: Int = 0,
    @ProtoNumber(4) @ProtoType(ProtoIntegerType.SIGNED) val y: Int = 0,
    @ProtoNumber(5) @ProtoType(ProtoIntegerType.SIGNED) val z: Int = 0,
    @ProtoNumber(6) val width: Int = 1,
    @ProtoNumber(7) val height: Int = 1,
    @ProtoNumber(8) val url: String = "",
    @ProtoNumber(9) val facing: Int = 0,
    @ProtoNumber(10) val isSync: Boolean = false,
    @ProtoNumber(11) val lang: String = "",
    @ProtoNumber(12) val isLocked: Boolean = true,
    @ProtoNumber(13) val mode: Int = 0,
    @ProtoNumber(14) val qualityCap: Int = 0,
    @ProtoNumber(15) val rotation: Int = 0,
    @ProtoNumber(16) val virtual: Boolean = false,
    @ProtoNumber(17) val forced: Boolean = false,
    @ProtoNumber(18) val scheduledStartEpochMillis: Long = 0,
    @ProtoNumber(19) val scheduledAction: Int = -1,
    @ProtoNumber(20) val access: Int = 2,
    @ProtoNumber(21) val inRegion: Boolean = false,
    @ProtoNumber(22) val viewerInRegion: Boolean = false,
    @ProtoNumber(23) val depth: Int = 1,
    @ProtoNumber(24) val conforming: Boolean = false,
    @ProtoNumber(25) val customData: Map<String, ByteArray> = emptyMap(),
    /**
     * Which video the display holds: a token the server changes whenever [url] or [lang] changes (0 from older
     * servers). Equality only, never ordered. Clients echo it in what they send about the video (see [MediaGen]).
     */
    @ProtoNumber(26) val mediaGen: Long = 0,
    /** Dimension key of the display's world (`minecraft:overworld`); empty from older servers and for virtual displays. */
    @ProtoNumber(27) val world: String = "",
) : DreamPacket

/** Removes a display (server broadcast) or requests its deletion (client action). */
@Serializable
data class DisplayDelete(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/**
 * Authoritative playback timeline for a display, pushed by server; [currentTimeMs] is position anchor, advancing at
 * [rate] content ms per server ms while playing.
 */
@Serializable
data class DisplaySync(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val isSync: Boolean = false,
    @ProtoNumber(3) val isPaused: Boolean = false,
    @ProtoNumber(4) val currentTimeMs: Long = 0,
    @ProtoNumber(5) val durationMs: Long = 0,
    @ProtoNumber(6) val serverTimeMs: Long = 0,
    @ProtoNumber(7) val loop: Boolean = false,
    @ProtoNumber(8) val mode: Int = 0,
    /** Playback speed (content ms per server ms); 1 when absent (older servers). */
    @ProtoNumber(9) val rate: Double = 1.0,
    /**
     * A start scheduled slightly ahead: playback begins at [currentTimeMs] this many ms after [serverTimeMs], so every
     * viewer can begin on the same frame. Only with [isPaused] false; clients that do not know the field just start now.
     */
    @ProtoNumber(10) val startInMs: Int = 0,
    /**
     * Karaoke: this player sings on the display and gets its timeline this many ms ahead (0 = not singing). The client
     * jumps straight to a changed lead instead of drifting there, and only a singer's client measures its own voice
     * timing (see `VoiceTrackAligner`).
     */
    @ProtoNumber(11) val karaokeLeadMs: Int = 0,
    /**
     * The [DisplayInfo.mediaGen] this timeline belongs to (0 from older servers). A client holding another one asks for
     * the display again ([RequestSync.knownMediaGen]).
     */
    @ProtoNumber(12) val mediaGen: Long = 0,
    /**
     * A speed change scheduled ahead ([startInMs] > 0, sent only to clients with `ClientFeature.SCHEDULED_SPEED`): the
     * timeline plays on at this speed until the start, where it is at [currentTimeMs] and goes on at [rate]. 0 = the
     * start of a stopped timeline (the client holds paused until then).
     */
    @ProtoNumber(13) val speedChangeFromRate: Double = 0.0,
) : DreamPacket

/**
 * Client -> server: whether this client holds a display ready to play at once (paused on a decoded frame with its
 * stream and sound open) and at which position. Lets the server end a start hold the moment every viewer is ready.
 * Sent only to servers advertising `display_readiness`; [state] is a `ReadinessState` wire value.
 */
@Serializable
data class DisplayReadiness(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val state: Int = 0,
    @ProtoNumber(3) val positionMs: Long = 0,
    /** The client's one-way latency to the server, in ms (half its round trip). */
    @ProtoNumber(4) val latencyMs: Int = 0,
    /** [DisplayInfo.mediaGen] of the video this report is about (0 from older clients). */
    @ProtoNumber(5) val mediaGen: Long = 0,
) : DreamPacket

/**
 * Client asks the server for the authoritative playback state of a display. [knownMediaGen]: the
 * [DisplayInfo.mediaGen] the client holds for it, 0 when it holds no description of the display (or, from clients
 * older than protocol 12, always 0). A server seeing another generation sends the display's [DisplayInfo] first.
 */
@Serializable
data class RequestSync(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val knownMediaGen: Long = 0,
) : DreamPacket

/**
 * Client applies a new media URL / audio language to a display, or with [clear] takes its video off ([url] and [lang]
 * then empty): the display shows nothing and its timeline stops. Anyone allowed to set a video may clear it. Only sent
 * with [clear] to servers that advertise `ServerFeature.CLEAR_VIDEO`; an empty [url] without [clear] is not a clear
 * (it is refused, as by every older server).
 */
@Serializable
data class SetVideo(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
    @ProtoNumber(4) val clear: Boolean = false,
) : DreamPacket

/**
 * Client changes who may use a display it owns. [locked] is what pre-1.10 clients send and all this
 * packet could once express; [access] carries the full level and wins whenever it is present.
 */
@Serializable
data class SetLocked(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val locked: Boolean = true,
    /** Wire ordinal of the requested `DisplayAccess`, or `-1` from a client that only knows [locked]. */
    @ProtoNumber(3) val access: Int = -1,
) : DreamPacket

/**
 * The access level [SetLocked] asks for: its own [SetLocked.access] when the sending client knows
 * about levels, otherwise the level its [SetLocked.locked] boolean maps onto.
 */
fun SetLocked.accessLevel(): DisplayAccess =
    if (access >= 0) DisplayAccess.fromWire(access) else DisplayAccess.fromLegacyLocked(locked)

/** Client reports a display to the server's configured webhook. */
@Serializable
data class ReportDisplay(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/** Display rendering toggle: bidirectional preference / admin control. */
@Serializable
data class SetDisplaysEnabled(
    @ProtoNumber(1) val enabled: Boolean = true,
) : DreamPacket

/** Server asks admin's client to render / hide fullscreen-broadcast radius preview. */
@Serializable
data class RadiusPreview(
    @ProtoNumber(1) val x: Double = 0.0,
    @ProtoNumber(2) val y: Double = 0.0,
    @ProtoNumber(3) val z: Double = 0.0,
    @ProtoNumber(4) val radius: Double = 0.0,
    @ProtoNumber(5) val show: Boolean = false,
    @ProtoNumber(6) val colorArgb: Int = 0,
) : DreamPacket
