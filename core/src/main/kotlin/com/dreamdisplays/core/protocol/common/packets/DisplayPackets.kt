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
) : DreamPacket

/** Removes a display (server broadcast) or requests its deletion (client action). */
@Serializable
data class DisplayDelete(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/** Authoritative playback timeline for a display, pushed by server; [currentTimeMs] is position anchor. */
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
    /**
     * A start scheduled slightly ahead: playback begins at [currentTimeMs] this many ms after [serverTimeMs], so every
     * viewer can begin on the same frame. Only with [isPaused] false; clients that do not know the field just start now.
     */
    @ProtoNumber(9) val startInMs: Int = 0,
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
) : DreamPacket

/** Client asks the server for the authoritative playback state of a display. */
@Serializable
data class RequestSync(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/** Client applies a new media URL / audio language to a display. */
@Serializable
data class SetVideo(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
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
