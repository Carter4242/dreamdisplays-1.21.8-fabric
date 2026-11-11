package com.dreamdisplays.platform.server.karaoke

import com.dreamdisplays.core.karaoke.AutoTrimController
import com.dreamdisplays.core.karaoke.ClockOffsetEstimator
import com.dreamdisplays.core.karaoke.ListenerLatency
import com.dreamdisplays.core.karaoke.SingerLatency
import com.dreamdisplays.core.karaoke.UplinkMeter
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPing
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPong
import com.dreamdisplays.core.protocol.common.packets.KaraokeLatencyReport
import com.dreamdisplays.core.protocol.common.packets.KaraokeMicMarkers
import io.github.arnodoelinger.platformweaver.PaperOnly
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * What clients measured for karaoke timing, per player, for this connection (forgotten on quit):
 *
 * - their latency report ([KaraokeLatencyReport]: display audio output, mic capture, voice chat buffer and output);
 * - for singers, the clock offset ([ClockOffsetEstimator], from pings the server sends them) and, with it, each voice
 *   frame's capture -> arrival time ([UplinkMeter], from their capture markers and the frames voice chat delivers);
 * - their automatic trim ([AutoTrimController]), from the offsets their client finds by listening to its own singing.
 *
 * Reports and pongs arrive on the main thread, voice frames on voice chat's thread; everything here is synchronised
 * per player. Nothing here sends anything except [pingSingers].
 */
@PaperOnly
object KaraokeMeasurements {
    /** A latency report, sanity-bounded, and when it came (ms, server clock). */
    class Report(val report: KaraokeLatencyReport, val atMs: Long)

    private class Player {
        val clock = ClockOffsetEstimator()
        val uplink = UplinkMeter()
        val autoTrim = AutoTrimController()
        var report: Report? = null
        var nextPingSeq = 1
        var lastPingNanos = Long.MIN_VALUE / 2
        var lastOffsetMs: Double? = null
        var lastOffsetConfidence = 0.0
        var lastOffsetAtMs = 0L
    }

    private val players = ConcurrentHashMap<UUID, Player>()

    private fun of(playerId: UUID): Player = players.computeIfAbsent(playerId) { Player() }

    fun onLatencyReport(playerId: UUID, packet: KaraokeLatencyReport, nowMs: Long) {
        val clean = KaraokeLatencyReport(
            trackOutputMs = bounded(packet.trackOutputMs, MAX_OUTPUT_MS),
            trackOutputMeasured = packet.trackOutputMeasured,
            trackQueuedMs = bounded(packet.trackQueuedMs, MAX_QUEUED_MS),
            voiceChat = packet.voiceChat,
            captureMs = bounded(packet.captureMs, MAX_OUTPUT_MS),
            voiceBufferMs = bounded(packet.voiceBufferMs, MAX_BUFFER_MS),
            voiceOutputMs = bounded(packet.voiceOutputMs, MAX_OUTPUT_MS),
            voiceOutputMeasured = packet.voiceOutputMeasured,
        )
        val player = of(playerId)
        synchronized(player) { player.report = Report(clean, nowMs) }
    }

    private fun bounded(ms: Int, max: Int): Int = if (ms < 0) -1 else ms.coerceAtMost(max)

    fun reportOf(playerId: UUID): Report? = players[playerId]?.let { synchronized(it) { it.report } }

    /** A pong, read at [receivedNanos] (server `System.nanoTime`). */
    fun onPong(playerId: UUID, pong: KaraokeClockPong, receivedNanos: Long) {
        val player = players[playerId] ?: return
        synchronized(player) {
            player.clock.onExchange(pong.serverNanos, pong.clientReceiveNanos, pong.clientSendNanos, receivedNanos)
        }
    }

    fun onMarkers(playerId: UUID, markers: KaraokeMicMarkers, nowNanos: Long) {
        val player = players[playerId] ?: return
        synchronized(player) {
            val offset = player.clock.offsetNanos(nowNanos) ?: return
            val n = minOf(markers.seqs.size, markers.captureNanos.size, MAX_MARKERS)
            for (i in 0 until n) player.uplink.onMarker(markers.seqs[i], markers.captureNanos[i], offset)
        }
    }

    /** A voice frame of [singerId] arrived (voice chat's thread). */
    fun onFrame(singerId: UUID, seq: Long, arrivalNanos: Long) {
        val player = players[singerId] ?: return
        synchronized(player) { player.uplink.onFrame(seq, arrivalNanos) }
    }

    /** A singer's offset report; true when it moved their automatic trim. */
    fun onOffset(singerId: UUID, offsetMs: Double, confidence: Double, nowMs: Long): Boolean {
        val player = of(singerId)
        synchronized(player) {
            player.lastOffsetMs = offsetMs
            player.lastOffsetConfidence = confidence
            player.lastOffsetAtMs = nowMs
            return player.autoTrim.onReport(offsetMs, confidence, nowMs)
        }
    }

    /** [singerId] starts singing (again): their offset reports start afresh; the trim learned so far stays. */
    fun onSingingStarted(singerId: UUID) {
        val player = of(singerId)
        synchronized(player) {
            player.autoTrim.resetReports()
            player.lastOffsetMs = null
        }
    }

    /** The singer-side latencies [singerId]'s client measured (empty when it reported nothing). */
    fun singerLatency(singerId: UUID, nowNanos: Long): SingerLatency {
        val player = players[singerId] ?: return SingerLatency()
        synchronized(player) {
            val report = player.report?.report
            val capture = report?.captureMs?.takeIf { report.voiceChat && it >= 0 }?.toLong()
            val output = report?.trackOutputMs?.takeIf { it >= 0 }?.toLong()
            val uplink = player.uplink.latencyNanos()?.let { it / 1_000_000L }?.takeIf { it <= MAX_UPLINK_MS }
            return SingerLatency(output, capture, uplink)
        }
    }

    /** The listener-side latencies [playerId]'s client reported, or null (no report, or no voice chat client data). */
    fun listenerLatency(playerId: UUID): ListenerLatency? {
        val report = reportOf(playerId)?.report ?: return null
        if (report.voiceBufferMs < 0) return null
        return ListenerLatency(
            voiceBufferMs = report.voiceBufferMs.toLong(),
            voiceOutputMs = report.voiceOutputMs.takeIf { it >= 0 }?.toLong(),
            trackOutputMs = report.trackOutputMs.takeIf { it >= 0 }?.toLong(),
        )
    }

    fun autoTrimMs(singerId: UUID): Long = players[singerId]?.let { synchronized(it) { it.autoTrim.trimMs } } ?: 0L

    /** Status figures for one singer; all read-only. */
    class SingerStatus(
        val autoTrimMs: Long,
        val confidence: Double,
        val reports: Int,
        val lastOffsetMs: Double?,
        val clockRttMs: Double?,
        val uplinkSamples: Int,
    )

    fun statusOf(singerId: UUID, nowNanos: Long): SingerStatus {
        val player = players[singerId] ?: return SingerStatus(0L, 0.0, 0, null, null, 0)
        synchronized(player) {
            return SingerStatus(
                autoTrimMs = player.autoTrim.trimMs,
                confidence = player.lastOffsetConfidence,
                reports = player.autoTrim.reports,
                lastOffsetMs = player.lastOffsetMs,
                clockRttMs = player.clock.rttNanos(nowNanos)?.let { it / 1e6 },
                uplinkSamples = player.uplink.samples,
            )
        }
    }

    /**
     * Sends clock pings to [singers] whose clients take them: quickly at first (until a few exchanges are in), then
     * every [PING_INTERVAL_NANOS]. Main thread.
     */
    fun pingSingers(singers: Collection<UUID>, capable: (UUID) -> Boolean, send: (UUID, KaraokeClockPing) -> Unit) {
        val now = System.nanoTime()
        for (singerId in singers) {
            if (!capable(singerId)) continue
            val player = of(singerId)
            val ping = synchronized(player) {
                val interval = if (player.clock.samples < WARMUP_SAMPLES) WARMUP_INTERVAL_NANOS else PING_INTERVAL_NANOS
                if (now - player.lastPingNanos < interval) return@synchronized null
                player.lastPingNanos = now
                KaraokeClockPing(player.nextPingSeq++, System.nanoTime())
            } ?: continue
            send(singerId, ping)
        }
    }

    fun forget(playerId: UUID) {
        players.remove(playerId)
    }

    fun clear() = players.clear()

    private const val MAX_OUTPUT_MS = 500
    private const val MAX_QUEUED_MS = 5_000
    private const val MAX_BUFFER_MS = 400
    private const val MAX_UPLINK_MS = 2_000L
    private const val MAX_MARKERS = 64
    private const val WARMUP_SAMPLES = 6
    private const val WARMUP_INTERVAL_NANOS = 250_000_000L
    private const val PING_INTERVAL_NANOS = 2_000_000_000L
}
