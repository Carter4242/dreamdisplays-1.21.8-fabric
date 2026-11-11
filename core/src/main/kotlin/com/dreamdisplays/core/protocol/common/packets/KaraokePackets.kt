@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/*
 * Karaoke timing measurements. Clients send these only to servers advertising `ServerFeature.KARAOKE`, and only
 * numbers: no audio ever leaves a client through them.
 */

/**
 * Client -> server, every few seconds: the latencies this client measured (or estimated) for karaoke voice timing, in
 * ms; -1 = unknown.
 *
 * @property trackOutputMs display audio: from the player's clock to the speakers (output device latency).
 * @property trackOutputMeasured [trackOutputMs] comes from the device itself (OpenAL), not a per-backend estimate.
 * @property trackQueuedMs display audio written to the line but not played yet (informational: the player's clock
 *   already counts played frames, so this is not added to anything).
 * @property voiceChat Simple Voice Chat is installed on this client.
 * @property captureMs mic: from a sound to voice chat handing over the 20 ms frame holding it.
 * @property voiceBufferMs voice chat's jitter buffer (`output_buffer_size` x 20 ms).
 * @property voiceOutputMs voice chat's audio output latency.
 * @property voiceOutputMeasured [voiceOutputMs] comes from the device itself.
 */
@Serializable
data class KaraokeLatencyReport(
    @ProtoNumber(1) val trackOutputMs: Int = -1,
    @ProtoNumber(2) val trackOutputMeasured: Boolean = false,
    @ProtoNumber(3) val trackQueuedMs: Int = -1,
    @ProtoNumber(4) val voiceChat: Boolean = false,
    @ProtoNumber(5) val captureMs: Int = -1,
    @ProtoNumber(6) val voiceBufferMs: Int = -1,
    @ProtoNumber(7) val voiceOutputMs: Int = -1,
    @ProtoNumber(8) val voiceOutputMeasured: Boolean = false,
) : DreamPacket

/**
 * Client -> server, from a singer's client only: how late its singer's voice is against the track they heard on
 * display [id] ([offsetMs], negative = early), found by correlating the two over the last [windowMs]. [confidence]
 * 0..1. The audio is analysed on the singer's own client and never sent; only this number is.
 */
@Serializable
data class KaraokeOffsetReport(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val offsetMs: Int = 0,
    @ProtoNumber(3) val confidence: Double = 0.0,
    @ProtoNumber(4) val windowMs: Int = 0,
) : DreamPacket

/** Server -> client: clock offset probe; the client answers at once with [KaraokeClockPong]. [serverNanos]: send time. */
@Serializable
data class KaraokeClockPing(
    @ProtoNumber(1) val seq: Int = 0,
    @ProtoNumber(2) val serverNanos: Long = 0,
) : DreamPacket

/** Client -> server: answer to [KaraokeClockPing], with the client's own receive and send times (`System.nanoTime`). */
@Serializable
data class KaraokeClockPong(
    @ProtoNumber(1) val seq: Int = 0,
    @ProtoNumber(2) val serverNanos: Long = 0,
    @ProtoNumber(3) val clientReceiveNanos: Long = 0,
    @ProtoNumber(4) val clientSendNanos: Long = 0,
) : DreamPacket

/**
 * Client -> server, from a singer's client only: voice chat mic frame [seqs] were handed over at [captureNanos] (client
 * `System.nanoTime`, on the frames' jitter-free grid). With the clock offset the server knows each frame's exact
 * capture -> arrival time. Pairs by index.
 */
@Serializable
data class KaraokeMicMarkers(
    @ProtoNumber(1) val seqs: List<Long> = [],
    @ProtoNumber(2) val captureNanos: List<Long> = [],
) : DreamPacket
