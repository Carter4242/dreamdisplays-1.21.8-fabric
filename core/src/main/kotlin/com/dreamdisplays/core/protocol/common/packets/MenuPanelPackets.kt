@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/**
 * Client -> server: which of display [id]'s menu panels this client has open. [activity]: the Activity tab is open
 * (send the list, then each new entry). [viewers]: the menu is open and shows the Viewers tab or its count (send the
 * list, then each change). Both false: the menu closed, send nothing more. A client follows one display at a time; a
 * request for another display replaces the previous one. Sent only to servers that advertise `activity_log` or
 * `viewer_list`.
 */
@Serializable
data class MenuPanelRequest(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val activity: Boolean = false,
    @ProtoNumber(3) val viewers: Boolean = false,
) : DreamPacket

/**
 * One row of a display's Activity list. [kind] is an `ActivityKind` wire value; which other fields matter depends on
 * it. [actorId] / [actorName] are empty for something that happened by itself (the queue moved on, the video looped),
 * and [console] marks an action by the server console. Strings are length-capped by the server.
 */
@Serializable
data class ActivityEntryWire(
    /** Increases with every new row of the display; a row coalesced with a later action keeps its number. */
    @ProtoNumber(1) val seq: Long = 0,
    /** Server wall time of the action (of the last one, for coalesced rows). */
    @ProtoNumber(2) val atMs: Long = 0,
    @ProtoNumber(3) val kind: Int = 0,
    @ProtoNumber(4) val actorId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(5) val actorName: String = "",
    @ProtoNumber(6) val title: String = "",
    @ProtoNumber(7) val url: String = "",
    @ProtoNumber(8) val fromMs: Long = 0,
    @ProtoNumber(9) val toMs: Long = 0,
    @ProtoNumber(10) val rate: Double = 0.0,
    @ProtoNumber(11) val count: Int = 0,
    @ProtoNumber(12) val flag: Boolean = false,
    @ProtoNumber(13) val text: String = "",
    @ProtoNumber(14) val text2: String = "",
    @ProtoNumber(15) val console: Boolean = false,
)

/**
 * Server -> client: rows of display [id]'s Activity list, newest first. [replace]: the whole list (the tab was just
 * opened); otherwise rows to add, or to put in place of the row with the same [ActivityEntryWire.seq]. [serverTimeMs]
 * lets the client show "2m ago" right whatever its own clock says. Sent only to clients that advertise `activity_log`
 * and asked for it with [MenuPanelRequest.activity].
 */
@Serializable
data class ActivityState(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val entries: List<ActivityEntryWire> = [],
    @ProtoNumber(3) val replace: Boolean = false,
    @ProtoNumber(4) val serverTimeMs: Long = 0,
) : DreamPacket

/** One player watching a display: [via] is a `ViewerVia` wire value, [status] a `ViewerStatus` one. */
@Serializable
data class ViewerEntryWire(
    @ProtoNumber(1) val playerId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val via: Int = 0,
    @ProtoNumber(4) val status: Int = 0,
)

/**
 * Server -> client: everyone who sees or hears display [id] right now, as far as the asking player may know (players
 * hidden from them are left out). Always the whole list (it is small and capped); [total] counts the players that
 * actually get the display (rows with no mod are listed but not counted). Sent only to clients that advertise
 * `viewer_list` and asked for it with [MenuPanelRequest.viewers], when the list changes.
 */
@Serializable
data class ViewersState(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val viewers: List<ViewerEntryWire> = [],
    @ProtoNumber(3) val total: Int = 0,
) : DreamPacket
