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
