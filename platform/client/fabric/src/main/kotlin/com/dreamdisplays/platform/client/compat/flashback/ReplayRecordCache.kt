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
 * A timeline is kept as the position this client was meant to show and the moment that position applies (when the
 * packet arrived, less the one-way latency the player adds when it follows it; later for a start scheduled ahead), so a
 * replay can put it on its own clock.
 */
class ReplayRecordCache {
    private class Known(var info: DisplayInfo? = null, var timeline: Timeline? = null)

    private class Timeline(val packet: DisplaySync, val anchorWallMs: Long)

    private val known = LinkedHashMap<UUID, Known>()

    /** Takes a packet from the server; returns the update to record, or null when it changes nothing replays show. */
    fun onPacket(packet: DreamPacket, nowMs: Long, oneWayLatencyMs: Double): ReplayFrame? = when (packet) {
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
                oneWayLatencyMs.roundToLong()
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

    fun clear() = known.clear()

    private fun remove(ids: List<UUID>): ReplayFrame? {
        val gone = ids.filter { known.remove(it) != null }
        return if (gone.isEmpty()) null else ReplayFrame(false, gone.map { ReplayEntry(it, removed = true) })
    }

    private fun entryFor(id: UUID, info: DisplayInfo, sample: ReplaySample?): ReplayEntry {
        val card = cardOf(info)
        // Nothing of a screen share is stored, not even its relay address; a local display's video is not replayed
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
