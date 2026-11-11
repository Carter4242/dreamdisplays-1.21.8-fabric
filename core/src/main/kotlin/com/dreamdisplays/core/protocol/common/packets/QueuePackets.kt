@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/**
 * One entry of a display's "Up next" queue. [title], [durationMs] and [thumbnailUrl] were resolved by the client that
 * added it and are display text only: the server length-caps and cleans them, clients never fetch [thumbnailUrl]
 * from a host they would not fetch a platform thumbnail from anyway.
 */
@Serializable
data class QueueItemWire(
    @ProtoNumber(1) val itemId: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
    @ProtoNumber(4) val title: String = "",
    @ProtoNumber(5) val durationMs: Long = 0,
    @ProtoNumber(6) val thumbnailUrl: String = "",
    @ProtoNumber(7) val addedBy: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(8) val addedByName: String = "",
)

/**
 * Server -> client: the whole queue of display [id] (small, capped by the server), re-sent on every change and in
 * reply to a [RequestSync]. Sent only to clients whose [ClientHello.supportsQueue] is set; a client that does not
 * know this packet type drops it.
 */
@Serializable
data class QueueState(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val items: List<QueueItemWire> = [],
    /** When true, a finished item goes back to the end of the queue instead of being dropped. */
    @ProtoNumber(3) val loop: Boolean = false,
    /** Server caps, so the menu can say "full" before the player tries. 0 = no cap. */
    @ProtoNumber(4) val maxItems: Int = 0,
    @ProtoNumber(5) val maxPerPlayer: Int = 0,
    /** Bumped on every change; lets a client ignore a late, older state. */
    @ProtoNumber(6) val revision: Long = 0,
    /** Item id of the queue entry now playing on the display, or empty when the video was set by hand. */
    @ProtoNumber(7) val currentItemId: String = "",
) : DreamPacket

/**
 * Client -> server: a queue edit on display [id]. Which fields matter depends on [action] ([QueueActionType] wire
 * value): ADD uses the media fields, ADD_BATCH uses [items] (a resolved playlist; their item ids and adders are
 * ignored), REMOVE / MOVE use [itemId] (MOVE also [toIndex]), LOOP uses [flag].
 */
@Serializable
data class QueueAction(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val action: Int = 0,
    @ProtoNumber(3) val itemId: String = "",
    @ProtoNumber(4) val url: String = "",
    @ProtoNumber(5) val lang: String = "",
    @ProtoNumber(6) val title: String = "",
    @ProtoNumber(7) val durationMs: Long = 0,
    @ProtoNumber(8) val thumbnailUrl: String = "",
    @ProtoNumber(9) val toIndex: Int = 0,
    @ProtoNumber(10) val flag: Boolean = false,
    @ProtoNumber(11) val items: List<QueueItemWire> = [],
) : DreamPacket

/**
 * Client -> server: how far this client got preparing queue item [itemId] of display [id] ahead of time, as a
 * `FullscreenPrepareState` wire value (the same states a fullscreen preparation reports). The server shortens the
 * start hold when every nearby client is READY for the item it advances to.
 */
@Serializable
data class QueuePreload(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val itemId: String = "",
    @ProtoNumber(3) val state: Int = 0,
) : DreamPacket

/** [QueueAction.action] values; append-only. */
enum class QueueActionType(val wire: Int) {
    ADD(1),
    REMOVE(2),
    MOVE(3),
    SKIP(4),
    CLEAR(5),
    LOOP(6),

    /** Several videos at once, in order (a playlist). */
    ADD_BATCH(7);

    companion object {
        /** Decodes [wire], or null for a value this build does not know. */
        fun fromWire(wire: Int): QueueActionType? = entries.firstOrNull { it.wire == wire }
    }
}
