@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/** Client playback intent for server-authoritative timeline. */
@Serializable
data class PlaybackCommand(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val action: Int = 0,
    @ProtoNumber(3) val positionMs: Long = 0,
    /** Requested playback speed for the `SPEED` action. */
    @ProtoNumber(4) val rate: Double = 1.0,
    /** [DisplayInfo.mediaGen] of the video the player acted on (0 from older clients); another one is ignored. */
    @ProtoNumber(5) val mediaGen: Long = 0,
) : DreamPacket

/** Client sets a display's persistent base mode. */
@Serializable
data class SetMode(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val mode: Int = 0,
    @ProtoNumber(3) val positionMs: Long = -1,
    /** [DisplayInfo.mediaGen] [positionMs] belongs to (0 from older clients); with another one it is not used. */
    @ProtoNumber(4) val mediaGen: Long = 0,
) : DreamPacket

/** Client pins / unpins display to PiP overlay; server persists per player. */
@Serializable
data class PipPin(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val pinned: Boolean = true,
) : DreamPacket

/**
 * Client reports the media duration of display [id]'s video. [url] is the video it measured (empty from older
 * clients): the server ignores a report for anything but the display's current video.
 *
 * Clients from protocol 12 on send one in every mode whenever what they know changes ([mediaGen], [url] or the
 * length), and in answer to a [ReportRequest]. [observedEnd]: the stream ended normally at [durationMs] (its header
 * had no length, or another one), measured rather than read from metadata.
 */
@Serializable
data class ReportDuration(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val durationMs: Long = 0,
    @ProtoNumber(3) val url: String = "",
    /** [DisplayInfo.mediaGen] of the measured video (0 from older clients). */
    @ProtoNumber(4) val mediaGen: Long = 0,
    @ProtoNumber(5) val observedEnd: Boolean = false,
) : DreamPacket

/**
 * Server -> client: send again what you know about display [displayId] ([com.dreamdisplays.core.protocol.common.ZERO_UUID]
 * = every display you know), for each [kinds] bit ([ReportKind]). The answer is the client's current state, sent even
 * when it is unchanged: a [DisplayReadiness] (`ABSENT` for a display it does not play), the [QueuePreload] of its
 * preloaded queue entry, a [ReportDuration] when it knows a length. Clients rate-limit their answers; older clients
 * drop this packet.
 */
@Serializable
data class ReportRequest(
    @ProtoNumber(1) val displayId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val kinds: Int = 0,
) : DreamPacket

/** [ReportRequest.kinds] bits; append-only. Unknown bits are ignored. */
object ReportKind {
    const val READINESS = 1
    const val PRELOAD = 2
    const val DURATION = 4

    /** True if [kinds] has [kind] set. */
    fun has(kinds: Int, kind: Int): Boolean = kinds and kind != 0
}

/**
 * [DisplayInfo.mediaGen] rules shared by both sides: 0 means unknown (an older peer), so it never makes anything
 * stale; any other value must equal the current one.
 */
object MediaGen {
    /**
     * Next generation to hand out. Starts at a random point per process, so a plugin reload never hands a client a
     * generation it already holds for another video; always positive and small enough for a short varint.
     */
    private val counter = java.util.concurrent.atomic.AtomicLong(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L, 1L shl 40))

    /** A generation no display of this process held before. */
    fun next(): Long = counter.incrementAndGet()

    /** True when [reported] (from a packet) does not contradict the display's [current] generation. */
    fun matches(reported: Long, current: Long): Boolean = reported == 0L || current == 0L || reported == current
}

/** Server tells client to pause / resume local-mode display player (no seek). */
@Serializable
data class RemotePlaybackToggle(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val paused: Boolean = true,
) : DreamPacket
