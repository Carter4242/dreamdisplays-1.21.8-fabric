package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.shared.protocol.PacketDirection
import com.dreamdisplays.core.protocol.common.packets.ClearCache
import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
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
import com.dreamdisplays.core.protocol.common.packets.PipPin
import com.dreamdisplays.core.protocol.common.packets.PlaybackCommand
import com.dreamdisplays.core.protocol.common.packets.QueueAction
import com.dreamdisplays.core.protocol.common.packets.QueuePreload
import com.dreamdisplays.core.protocol.common.packets.QueueState
import com.dreamdisplays.core.protocol.common.packets.RadiusPreview
import com.dreamdisplays.core.protocol.common.packets.RemotePlaybackToggle
import com.dreamdisplays.core.protocol.common.packets.ReportDisplay
import com.dreamdisplays.core.protocol.common.packets.ReportDuration
import com.dreamdisplays.core.protocol.common.packets.RequestSync
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.core.protocol.common.packets.ServerPolicy
import com.dreamdisplays.core.protocol.common.packets.ViewerReport
import com.dreamdisplays.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplays.core.protocol.common.packets.SetLocked
import com.dreamdisplays.core.protocol.common.packets.SetMode
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import com.dreamdisplays.core.protocol.common.packets.WatchPartyControl
import com.dreamdisplays.core.protocol.common.packets.WatchPartyStart
import com.dreamdisplays.core.protocol.common.packets.WatchPartyState
import kotlin.reflect.KClass

/**
 * Append-only protocol-v2 packet type ids; wire-protocol stable, never reuse or renumber.
 */
enum class PacketType(
    val id: Int,
    val packetClass: KClass<out DreamPacket>,
    val direction: PacketDirection,
) {
    CLIENT_HELLO(1, ClientHello::class, PacketDirection.CLIENT_TO_SERVER),
    SERVER_HELLO(2, ServerHello::class, PacketDirection.SERVER_TO_CLIENT),
    DISPLAY_INFO(3, DisplayInfo::class, PacketDirection.SERVER_TO_CLIENT),
    DISPLAY_DELETE(4, DisplayDelete::class, PacketDirection.BIDIRECTIONAL),
    DISPLAY_SYNC(5, DisplaySync::class, PacketDirection.BIDIRECTIONAL),
    REQUEST_SYNC(6, RequestSync::class, PacketDirection.CLIENT_TO_SERVER),
    SET_VIDEO(7, SetVideo::class, PacketDirection.CLIENT_TO_SERVER),
    SET_LOCKED(8, SetLocked::class, PacketDirection.CLIENT_TO_SERVER),
    REPORT_DISPLAY(9, ReportDisplay::class, PacketDirection.CLIENT_TO_SERVER),
    SET_DISPLAYS_ENABLED(10, SetDisplaysEnabled::class, PacketDirection.BIDIRECTIONAL),
    CLEAR_CACHE(11, ClearCache::class, PacketDirection.SERVER_TO_CLIENT),
    PLAYBACK_COMMAND(12, PlaybackCommand::class, PacketDirection.CLIENT_TO_SERVER),
    SET_MODE(13, SetMode::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_START(14, WatchPartyStart::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_CONTROL(15, WatchPartyControl::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_STATE(16, WatchPartyState::class, PacketDirection.SERVER_TO_CLIENT),
    FULLSCREEN_STATE(17, FullscreenState::class, PacketDirection.SERVER_TO_CLIENT),
    FULLSCREEN_ACK(18, FullscreenAck::class, PacketDirection.CLIENT_TO_SERVER),
    RADIUS_PREVIEW(19, RadiusPreview::class, PacketDirection.SERVER_TO_CLIENT),
    PIP_PIN(20, PipPin::class, PacketDirection.CLIENT_TO_SERVER),
    REPORT_DURATION(21, ReportDuration::class, PacketDirection.CLIENT_TO_SERVER),
    REMOTE_PLAYBACK_TOGGLE(22, RemotePlaybackToggle::class, PacketDirection.SERVER_TO_CLIENT),

    // Diagnostics / remote client control; ids start at 40 so packets added elsewhere in the 20s cannot collide
    SERVER_POLICY(40, ServerPolicy::class, PacketDirection.SERVER_TO_CLIENT),
    VIEWER_REPORT(41, ViewerReport::class, PacketDirection.CLIENT_TO_SERVER),

    // Fullscreen preparation; only sent to clients that advertise ClientHello.supportsFullscreenPrepare
    FULLSCREEN_PREPARE(42, FullscreenPrepare::class, PacketDirection.SERVER_TO_CLIENT),
    FULLSCREEN_PREPARE_STATUS(43, FullscreenPrepareStatus::class, PacketDirection.CLIENT_TO_SERVER),
    DISPLAY_READINESS(44, DisplayReadiness::class, PacketDirection.CLIENT_TO_SERVER),

    // Per-display "Up next" queue; ids start at 50 so packets added elsewhere in the 40s cannot collide.
    // QUEUE_STATE is only sent to clients that advertise ClientHello.supportsQueue.
    QUEUE_STATE(50, QueueState::class, PacketDirection.SERVER_TO_CLIENT),
    QUEUE_ACTION(51, QueueAction::class, PacketDirection.CLIENT_TO_SERVER),
    QUEUE_PRELOAD(52, QueuePreload::class, PacketDirection.CLIENT_TO_SERVER),

    // Client error reporting; only sent to servers that advertise ServerFeature.CLIENT_ERROR_REPORTS
    CLIENT_ERROR_REPORT(60, ClientErrorReport::class, PacketDirection.CLIENT_TO_SERVER),
    CLIENT_ENVIRONMENT(61, ClientEnvironment::class, PacketDirection.CLIENT_TO_SERVER),

    // Karaoke timing measurements; only exchanged with servers that advertise ServerFeature.KARAOKE
    KARAOKE_LATENCY_REPORT(70, KaraokeLatencyReport::class, PacketDirection.CLIENT_TO_SERVER),
    KARAOKE_OFFSET_REPORT(71, KaraokeOffsetReport::class, PacketDirection.CLIENT_TO_SERVER),
    KARAOKE_CLOCK_PING(72, KaraokeClockPing::class, PacketDirection.SERVER_TO_CLIENT),
    KARAOKE_CLOCK_PONG(73, KaraokeClockPong::class, PacketDirection.CLIENT_TO_SERVER),
    KARAOKE_MIC_MARKERS(74, KaraokeMicMarkers::class, PacketDirection.CLIENT_TO_SERVER);

    companion object {
        private val byId = entries.associateBy { it.id }

        init {
            require(byId.size == entries.size) { "Duplicate protocol packet type ids." }
        }
    }
}
