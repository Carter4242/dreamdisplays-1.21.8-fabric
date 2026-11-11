@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/**
 * Client asks to start ([start] true) or stop sharing its screen to display [id]. Only sent to servers that advertise
 * `ServerFeature.SCREEN_SHARE`; the server answers with [ScreenShareState].
 *
 * [heartbeat] (with [start] false): the client is still capturing to [id]. Sent about once a second, only to servers
 * that advertise `ServerFeature.SCREEN_SHARE_HEARTBEAT`; such a server ends a share whose heartbeats stop, and answers a
 * heartbeat for a share it does not know with an inactive [ScreenShareState] (the client then stops capturing). A
 * server without that feature would read it as a stop request, so it is never sent there.
 */
@Serializable
data class ScreenShareRequest(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val start: Boolean = false,
    @ProtoNumber(3) val heartbeat: Boolean = false,
) : DreamPacket

/**
 * Server tells the sharing player where their share stands on display [id]. [active] with a [publishUrl]: start (or
 * keep) encoding to it. Not [active]: stop encoding; a non-empty [error] says why it was refused or ended. Only ever sent
 * to the sharing player: [publishUrl] may carry the relay's publish credentials.
 */
@Serializable
data class ScreenShareState(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val active: Boolean = false,
    @ProtoNumber(3) val publishUrl: String = "",
    @ProtoNumber(4) val error: String = "",
    /** Server's encoder rule for this share: [ENCODER_AUTO] (empty: the client picks) or [ENCODER_SOFTWARE] (x264 only). */
    @ProtoNumber(5) val encoder: String = "",
    /** Frame rate cap for this share, 0 = the client's default. */
    @ProtoNumber(6) val maxFps: Int = 0,
    /** Picture height cap for this share (the width follows), 0 = the client's default. */
    @ProtoNumber(7) val maxHeight: Int = 0,
) : DreamPacket {
    companion object {
        const val ENCODER_AUTO = "auto"
        const val ENCODER_SOFTWARE = "software"
    }

    /** Never prints [publishUrl]: it carries the relay's publish credentials. */
    override fun toString(): String =
        "ScreenShareState(id=$id, active=$active, publishUrl=${if (publishUrl.isEmpty()) "" else "<redacted>"}, error=$error, " +
            "encoder=$encoder, maxFps=$maxFps, maxHeight=$maxHeight)"
}
