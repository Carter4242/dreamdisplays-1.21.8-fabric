package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.ClearCache
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.share.ScreenSharePrivacy
import com.dreamdisplays.platform.client.replay.ReplayCard
import java.util.*
import kotlin.math.roundToLong

/**
 * Recording side: what the server last said about each display (description and timeline), kept from the packets as
 * they arrive so a recording can start with the full picture. Client thread only. Times are wall ms (monotonic).
 *
 * A timeline is kept as a position and the moment it applies on this client's clock, so a replay can put it on its own
 * clock. That moment comes from the server's own stamp, mapped to this clock by the smallest gap ever seen between a
 * stamp and its arrival (clock skew plus the fastest delivery): a packet held up on the way does not move the timeline,
 * as the player's latency estimate would (it swings by hundreds of ms under load). Packets without a stamp fall back to
 * the arrival less the one-way latency. A start scheduled ahead applies that much later.
 */
class ReplayRecordCache {
    private class Known(var info: DisplayInfo? = null, var timeline: Timeline? = null)

    private class Timeline(val packet: DisplaySync, val anchorWallMs: Long)

    private val known = LinkedHashMap<UUID, Known>()

    /** Smallest (arrival epoch ms - server stamp) seen on this connection, or null before the first stamp. */
    private var clockOffsetMs: Long? = null

    /** Takes a packet from the server; returns the update to record, or null when it changes nothing replays show. */
    fun onPacket(packet: DreamPacket, nowMs: Long, nowEpochMs: Long, oneWayLatencyMs: Double): ReplayFrame? = when (packet) {
        is DisplayInfo -> {
            if (packet.virtual) {
                null
            } else {
                known.getOrPut(packet.id) { Known() }.info = packet
                ReplayFrame(false, listOf(entryFor(packet.id, info = packet, sample = null)))
            }
        }

        is DisplaySync -> {
            val anchor = nowMs + (if (!packet.isPaused && packet.startInMs > 0) packet.startInMs else 0) -
                arrivalDelayMs(packet.serverTimeMs, nowEpochMs, oneWayLatencyMs)
            val timeline = Timeline(packet, anchor)
            known.getOrPut(packet.id) { Known() }.timeline = timeline
            ReplayFrame(false, listOf(ReplayEntry(packet.id, sample = sampleOf(timeline, nowMs))))
        }

        is DisplayDelete -> remove(listOf(packet.id))
        is ClearCache -> remove(packet.ids)
        else -> null
    }

    /** Every described display [include] accepts, as a full frame written at [nowMs]. */
    fun fullFrame(nowMs: Long, include: (DisplayInfo) -> Boolean): ReplayFrame {
        val entries = known.mapNotNull { (id, k) ->
            val info = k.info ?: return@mapNotNull null
            if (!include(info)) return@mapNotNull null
            entryFor(id, info, k.timeline?.let { sampleOf(it, nowMs) })
        }
        return ReplayFrame(true, entries)
    }

    fun clear() {
        known.clear()
        clockOffsetMs = null
    }

    /** How long after its stamp this packet arrived, beyond the fastest delivery seen (see the class comment). */
    private fun arrivalDelayMs(serverTimeMs: Long, nowEpochMs: Long, oneWayLatencyMs: Double): Long {
        if (serverTimeMs <= 0L) return oneWayLatencyMs.roundToLong()
        val gap = nowEpochMs - serverTimeMs
        val offset = clockOffsetMs?.let { minOf(it, gap) } ?: gap
        clockOffsetMs = offset
        return gap - offset
    }

    private fun remove(ids: List<UUID>): ReplayFrame? {
        val gone = ids.filter { known.remove(it) != null }
        return if (gone.isEmpty()) null else ReplayFrame(false, gone.map { ReplayEntry(it, removed = true) })
    }

    private fun entryFor(id: UUID, info: DisplayInfo, sample: ReplaySample?): ReplayEntry {
        val card = cardOf(info)
        // This action keeps no screen share address (Flashback's own recording of the network traffic still holds the
        // raw packets, the share's relay address among them, and for the sharer its publish URL: both stop working when
        // the share ends); a local display's video is not replayed
        val stored = if (card == null) info else info.copy(url = "", lang = "")
        return ReplayEntry(id, stored, card, sample)
    }

    private fun sampleOf(t: Timeline, nowMs: Long): ReplaySample = ReplaySample(
        mediaGen = t.packet.mediaGen,
        positionMs = t.packet.currentTimeMs,
        anchorMs = t.anchorWallMs - nowMs,
        rate = t.packet.rate,
        paused = t.packet.isPaused,
        loop = t.packet.loop,
        durationMs = t.packet.durationMs,
    )

    companion object {
        /** Why [info] is shown as a card in replays, or null when its video is replayed. */
        fun cardOf(info: DisplayInfo): ReplayCard? = when {
            ScreenSharePrivacy.isPrivateStream(info.url) -> ReplayCard.SHARE
            info.mode == PlaybackMode.LOCAL.wire && !info.isSync && info.url.isNotBlank() -> ReplayCard.LOCAL
            else -> null
        }
    }
}
