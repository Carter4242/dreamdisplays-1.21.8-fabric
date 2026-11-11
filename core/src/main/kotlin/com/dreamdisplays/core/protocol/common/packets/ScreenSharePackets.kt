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
 */
@Serializable
data class ScreenShareRequest(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val start: Boolean = false,
) : DreamPacket

/**
 * Server tells the sharing player where their share stands on display [id]. [active] with a [publishUrl]: start (or
 * keep) encoding to it. Not [active]: stop encoding; a non-empty [error] says why it was refused or ended.
 */
@Serializable
data class ScreenShareState(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val active: Boolean = false,
    @ProtoNumber(3) val publishUrl: String = "",
    @ProtoNumber(4) val error: String = "",
) : DreamPacket
