package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.platform.client.replay.ReplayCard
import java.util.*

/** A display as a replay shows it at one moment: its description, card, and timeline (null until one is known). */
data class ReplayView(val info: DisplayInfo, val card: ReplayCard?, val sample: ReplaySample?)

/**
 * Playback side: the history of every display in the replay, filled by the replay actions as Flashback handles them
 * (replay-server thread) and read for any replay time by the driver (client thread).
 *
 * It is a history, not a current state: Flashback handles actions again after a seek back, and the driver only ever
 * asks what held at time T, so neither re-delivery nor bursts matter. The same tick written again replaces itself.
 */
class ReplayModel {
    private class Described(val info: DisplayInfo, val card: ReplayCard?)

    // Per display: what it looked like from each tick on (null = not there)
    private val infos = HashMap<UUID, TreeMap<Int, Optional<Described>>>()
    private val samples = HashMap<UUID, TreeMap<Int, ReplaySample>>()

    @Synchronized
    fun apply(tick: Int, frame: ReplayFrame) {
        val anchorBase = tick.toLong() * MS_PER_TICK
        val listed = HashSet<UUID>()
        for (e in frame.entries) {
            listed += e.id
            when {
                e.removed -> infos.getOrPut(e.id) { TreeMap() }[tick] = Optional.empty()
                e.info != null -> infos.getOrPut(e.id) { TreeMap() }[tick] = Optional.of(Described(e.info, e.card))
            }
            e.sample?.let { sample ->
                val history = samples.getOrPut(e.id) { TreeMap() }
                // A full frame repeats the timeline the client last got: it keeps the place it already has. Placed again
                // from this tick, it would move by however far the recorder's ticks drifted from its clock meanwhile
                // (a lagging client runs fewer ticks, then catches up), and the video would jump in the replay.
                val held = history.floorEntry(tick)?.value
                if (frame.full && held != null && held.sameTimelineAs(sample) && history.floorKey(tick) != tick) return@let
                history[tick] = sample.copy(anchorMs = anchorBase + sample.anchorMs)
            }
        }
        if (frame.full) {
            for ((id, history) in infos) if (id !in listed) history[tick] = Optional.empty()
        }
    }

    /** Every display there at replay time [replayMs], with the timeline that holds for its current video. */
    @Synchronized
    fun at(replayMs: Long): Map<UUID, ReplayView> {
        val tick = Math.floorDiv(replayMs, MS_PER_TICK).toInt()
        val out = HashMap<UUID, ReplayView>()
        for ((id, history) in infos) {
            val d = history.floorEntry(tick)?.value?.orElse(null) ?: continue
            val sample = samples[id]?.floorEntry(tick)?.value
                ?.takeIf { it.mediaGen == 0L || d.info.mediaGen == 0L || it.mediaGen == d.info.mediaGen }
            out[id] = ReplayView(d.info, d.card, sample)
        }
        return out
    }

    @Synchronized
    fun clear() {
        infos.clear()
        samples.clear()
    }

    companion object {
        const val MS_PER_TICK = 50L
    }
}
