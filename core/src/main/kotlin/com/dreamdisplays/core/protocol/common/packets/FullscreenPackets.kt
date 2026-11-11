@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/** Server snapshot of fullscreen broadcast targeting receiving player. */
@Serializable
data class FullscreenState(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val displayId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(3) val active: Boolean = false,
    @ProtoNumber(4) val mode: Int = 0,
    @ProtoNumber(5) val forced: Boolean = false,
    @ProtoNumber(6) val volume: Float = -1f,
    @ProtoNumber(7) val title: String = "",
    @ProtoNumber(8) val loop: Boolean = false,
    @ProtoNumber(9) val quality: String = "",
    @ProtoNumber(10) val minimized: Boolean = false,
) : DreamPacket

/** Client ack for [FullscreenState]: shown (0), dismissed (1), minimized (2). */
@Serializable
data class FullscreenAck(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val action: Int = 0,
) : DreamPacket

/**
 * Server asks the client to load [displayId] ahead of a fullscreen broadcast and hold it hidden, muted and ready to
 * play at once, until a [FullscreenState] for the display starts the broadcast or the server cancels ([active] =
 * false). Sent only to clients whose [ClientHello.supportsFullscreenPrepare] is set; a client that does not know this
 * packet type drops it.
 */
@Serializable
data class FullscreenPrepare(
    @ProtoNumber(1) val prepareId: String = "",
    @ProtoNumber(2) val displayId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(3) val active: Boolean = false,
    /** Quality the broadcast will use, so the warm player already opens at it; empty = the viewer's own. */
    @ProtoNumber(4) val quality: String = "",
    /** How long the client may hold the display ready without hearing back, in ms; 0 = until cancelled. */
    @ProtoNumber(5) val ttlMs: Long = 0,
) : DreamPacket

/** Client progress on a [FullscreenPrepare]; [state] is a `FullscreenPrepareState` wire value. */
@Serializable
data class FullscreenPrepareStatus(
    @ProtoNumber(1) val prepareId: String = "",
    @ProtoNumber(2) val displayId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(3) val state: Int = 0,
) : DreamPacket
