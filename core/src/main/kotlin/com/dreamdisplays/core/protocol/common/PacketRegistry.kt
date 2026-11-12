@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.shared.protocol.PacketDirection
import com.dreamdisplays.core.protocol.common.packets.ActivityState
import com.dreamdisplays.core.protocol.common.packets.ClearCache
import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplayReadiness
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.FullscreenAck
import com.dreamdisplays.core.protocol.common.packets.FullscreenPrepare
import com.dreamdisplays.core.protocol.common.packets.FullscreenPrepareStatus
import com.dreamdisplays.core.protocol.common.packets.FullscreenState
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPing
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPong
import com.dreamdisplays.core.protocol.common.packets.KaraokeLatencyReport
import com.dreamdisplays.core.protocol.common.packets.KaraokeMicMarkers
import com.dreamdisplays.core.protocol.common.packets.KaraokeOffsetReport
import com.dreamdisplays.core.protocol.common.packets.MenuPanelRequest
import com.dreamdisplays.core.protocol.common.packets.PipPin
import com.dreamdisplays.core.protocol.common.packets.PlaybackCommand
import com.dreamdisplays.core.protocol.common.packets.QueueAction
import com.dreamdisplays.core.protocol.common.packets.QueuePreload
import com.dreamdisplays.core.protocol.common.packets.QueueState
import com.dreamdisplays.core.protocol.common.packets.RadiusPreview
import com.dreamdisplays.core.protocol.common.packets.RemotePlaybackToggle
import com.dreamdisplays.core.protocol.common.packets.ReportDisplay
import com.dreamdisplays.core.protocol.common.packets.ReportDuration
import com.dreamdisplays.core.protocol.common.packets.ReportRequest
import com.dreamdisplays.core.protocol.common.packets.ScreenShareRequest
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.core.protocol.common.packets.RequestSync
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.core.protocol.common.packets.ServerPolicy
import com.dreamdisplays.core.protocol.common.packets.ViewerReport
import com.dreamdisplays.core.protocol.common.packets.ViewersState
import com.dreamdisplays.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplays.core.protocol.common.packets.SetLocked
import com.dreamdisplays.core.protocol.common.packets.SetMode
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.reflect.KClass
import kotlin.reflect.cast

/** Wire frame for the `dreamdisplays:v2` channel: a type id plus the encoded packet bytes. */
@Serializable
data class Envelope(
    @ProtoNumber(1) val type: Int = 0,
    @ProtoNumber(2) val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is Envelope && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

/** Maps type ids to packet serializers; wraps packets into [Envelope] for wire encoding / decoding. */
object PacketRegistry {
    private val proto = ProtoBuf { encodeDefaults = false }

    private class Entry<T : DreamPacket>(
        val packetType: PacketType,
        val type: KClass<T>,
        val serializer: KSerializer<T>,
    ) {
        val id: Int get() = packetType.id
        val direction: PacketDirection get() = packetType.direction

        fun encode(proto: ProtoBuf, packet: DreamPacket): ByteArray = proto.encodeToByteArray(serializer, type.cast(packet))
    }

    private val entries: List<Entry<out DreamPacket>> = [
        Entry(PacketType.CLIENT_HELLO, ClientHello::class, ClientHello.serializer()),
        Entry(PacketType.SERVER_HELLO, ServerHello::class, ServerHello.serializer()),
        Entry(PacketType.DISPLAY_INFO, DisplayInfo::class, DisplayInfo.serializer()),
        Entry(PacketType.DISPLAY_DELETE, DisplayDelete::class, DisplayDelete.serializer()),
        Entry(PacketType.DISPLAY_SYNC, DisplaySync::class, DisplaySync.serializer()),
        Entry(PacketType.REQUEST_SYNC, RequestSync::class, RequestSync.serializer()),
        Entry(PacketType.SET_VIDEO, SetVideo::class, SetVideo.serializer()),
        Entry(PacketType.SET_LOCKED, SetLocked::class, SetLocked.serializer()),
        Entry(PacketType.REPORT_DISPLAY, ReportDisplay::class, ReportDisplay.serializer()),
        Entry(PacketType.SET_DISPLAYS_ENABLED, SetDisplaysEnabled::class, SetDisplaysEnabled.serializer()),
        Entry(PacketType.CLEAR_CACHE, ClearCache::class, ClearCache.serializer()),
        Entry(PacketType.PLAYBACK_COMMAND, PlaybackCommand::class, PlaybackCommand.serializer()),
        Entry(PacketType.SET_MODE, SetMode::class, SetMode.serializer()),
        Entry(PacketType.FULLSCREEN_STATE, FullscreenState::class, FullscreenState.serializer()),
        Entry(PacketType.FULLSCREEN_ACK, FullscreenAck::class, FullscreenAck.serializer()),
        Entry(PacketType.RADIUS_PREVIEW, RadiusPreview::class, RadiusPreview.serializer()),
        Entry(PacketType.PIP_PIN, PipPin::class, PipPin.serializer()),
        Entry(PacketType.REPORT_DURATION, ReportDuration::class, ReportDuration.serializer()),
        Entry(PacketType.REMOTE_PLAYBACK_TOGGLE, RemotePlaybackToggle::class, RemotePlaybackToggle.serializer()),
        Entry(PacketType.REPORT_REQUEST, ReportRequest::class, ReportRequest.serializer()),
        Entry(PacketType.SERVER_POLICY, ServerPolicy::class, ServerPolicy.serializer()),
        Entry(PacketType.VIEWER_REPORT, ViewerReport::class, ViewerReport.serializer()),
        Entry(PacketType.FULLSCREEN_PREPARE, FullscreenPrepare::class, FullscreenPrepare.serializer()),
        Entry(
            PacketType.FULLSCREEN_PREPARE_STATUS, FullscreenPrepareStatus::class, FullscreenPrepareStatus.serializer(),
        ),
        Entry(PacketType.DISPLAY_READINESS, DisplayReadiness::class, DisplayReadiness.serializer()),
        Entry(PacketType.QUEUE_STATE, QueueState::class, QueueState.serializer()),
        Entry(PacketType.QUEUE_ACTION, QueueAction::class, QueueAction.serializer()),
        Entry(PacketType.QUEUE_PRELOAD, QueuePreload::class, QueuePreload.serializer()),
        Entry(PacketType.CLIENT_ERROR_REPORT, ClientErrorReport::class, ClientErrorReport.serializer()),
        Entry(PacketType.CLIENT_ENVIRONMENT, ClientEnvironment::class, ClientEnvironment.serializer()),
        Entry(PacketType.KARAOKE_LATENCY_REPORT, KaraokeLatencyReport::class, KaraokeLatencyReport.serializer()),
        Entry(PacketType.KARAOKE_OFFSET_REPORT, KaraokeOffsetReport::class, KaraokeOffsetReport.serializer()),
        Entry(PacketType.KARAOKE_CLOCK_PING, KaraokeClockPing::class, KaraokeClockPing.serializer()),
        Entry(PacketType.KARAOKE_CLOCK_PONG, KaraokeClockPong::class, KaraokeClockPong.serializer()),
        Entry(PacketType.KARAOKE_MIC_MARKERS, KaraokeMicMarkers::class, KaraokeMicMarkers.serializer()),
        Entry(PacketType.SCREEN_SHARE_REQUEST, ScreenShareRequest::class, ScreenShareRequest.serializer()),
        Entry(PacketType.SCREEN_SHARE_STATE, ScreenShareState::class, ScreenShareState.serializer()),
        Entry(PacketType.MENU_PANEL_REQUEST, MenuPanelRequest::class, MenuPanelRequest.serializer()),
        Entry(PacketType.ACTIVITY_STATE, ActivityState::class, ActivityState.serializer()),
        Entry(PacketType.VIEWERS_STATE, ViewersState::class, ViewersState.serializer()),
    ]

    private val byId = entries.associateBy { it.id }
    private val byType = entries.associateBy { it.type }

    init {
        require(byId.size == entries.size) { "Duplicate packet type ids." }
        require(byType.size == entries.size) { "Duplicate packet classes." }
        require(entries.map { it.packetType }.toSet() == PacketType.entries.toSet()) {
            "PacketRegistry must bind every PacketType exactly once."
        }
        entries.forEach { entry ->
            require(entry.type == entry.packetType.packetClass) {
                "Packet type ${entry.packetType} is bound to ${entry.packetType.packetClass.simpleName}, " +
                        "but registry entry uses ${entry.type.simpleName}."
            }
        }
    }

    fun encode(packet: DreamPacket): ByteArray {
        val entry = entryOf(packet)
        val payload = entry.encode(proto, packet)
        return proto.encodeToByteArray(Envelope.serializer(), Envelope(entry.id, payload))
    }

    fun decode(bytes: ByteArray): DreamPacket? {
        val envelope = proto.decodeFromByteArray(Envelope.serializer(), bytes)
        val entry = byId[envelope.type] ?: return null
        return proto.decodeFromByteArray(entry.serializer, envelope.payload)
    }

    fun decode(bytes: ByteArray, inbound: PacketDirection): DreamPacket? {
        val packet = decode(bytes) ?: return null
        val direction = directionOf(packet)
        require(direction == inbound || direction == PacketDirection.BIDIRECTIONAL) {
            "Packet ${packet::class.simpleName} travels $direction; not acceptable inbound as $inbound."
        }
        return packet
    }

    fun directionOf(packet: DreamPacket): PacketDirection = entryOf(packet).direction

    val schemaDescriptors: List<SerialDescriptor>
        get() = entries.map { it.serializer.descriptor }

    private fun entryOf(packet: DreamPacket): Entry<out DreamPacket> =
        byType[packet::class] ?: error("Unregistered packet type: ${packet::class.simpleName}.")
}
