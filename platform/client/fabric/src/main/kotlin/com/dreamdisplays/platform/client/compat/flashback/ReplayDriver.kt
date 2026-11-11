package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.platform.client.replay.ReplayCard
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.shared.protocol.ProtocolVersion
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Stands in for the server while a replay plays: every client tick it compares what the replay says each display
 * shows at the replay's current time with what it last told the client, and sends the client the same packets a
 * server would (descriptions, deletions, timelines). The client's own sync engine does the rest unchanged.
 *
 * Replay pause, seeking and frozen time become a paused timeline at the replay position (the player snaps onto that
 * exact frame); replay speed scales the timeline's rate. Client thread only, except [requestSync].
 */
class ReplayDriver(private val send: (DreamPacket) -> Unit) {
    /** The replay clock: [replayMs] its current time, [speed] replay ms per real ms, [held] not advancing. */
    data class Clock(val replayMs: Long, val held: Boolean, val speed: Double)

    private class Told(
        var info: DisplayInfo,
        var infoAtMs: Long,
        var timeline: Told.Timeline? = null,
    ) {
        class Timeline(val paused: Boolean, val rate: Double, val positionMs: Long, val atMs: Long, val mediaGen: Long)

        /** Where the client's player is headed now, from the last timeline it got. */
        fun expectedMs(nowMs: Long): Long? {
            val t = timeline ?: return null
            return if (t.paused) t.positionMs else t.positionMs + ((nowMs - t.atMs) * t.rate).roundToLong()
        }
    }

    private val told = HashMap<UUID, Told>()
    private val syncWanted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val infoWanted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val cards = ConcurrentHashMap<UUID, ReplayCard>()
    private var lastStamp = 0L

    /** The card display [id] shows instead of media, or null. Any thread. */
    fun card(id: UUID): ReplayCard? = cards[id]

    /** The client asked for display [id]'s state (it holds media generation [knownMediaGen]). Any thread. */
    fun requestSync(id: UUID, knownMediaGen: Long) {
        if (knownMediaGen == 0L) infoWanted += id
        syncWanted += id
    }

    /** The hello a replay answers with: only what display playback needs, nothing that talks back to a server. */
    fun hello(): ServerHello = ServerHello(
        protocolVersion = ProtocolVersion.CURRENT,
        allowedFeatures = listOf(ServerFeature.MODES, ServerFeature.BROADCAST, ServerFeature.PLAYBACK_SPEED).map { it.wire },
    )

    /**
     * One step. [views]: what the replay holds at [clock]; [clientHas]: whether the client still holds a display (a level
     * reload drops them); [oneWayLatencyMs]: what the client's follower adds to a playing timeline.
     */
    fun tick(nowMs: Long, clock: Clock, views: Map<UUID, ReplayView>, clientHas: (UUID) -> Boolean, oneWayLatencyMs: Double) {
        for (id in told.keys.toList()) {
            if (id !in views) {
                told.remove(id)
                cards.remove(id)
                send(DisplayDelete(id))
            }
        }
        for ((id, view) in views) {
            val info = shown(view)
            val prior = told[id]
            val wantsInfo = infoWanted.remove(id)
            val describe = prior == null || !sameInfo(prior.info, info) || wantsInfo ||
                (!clientHas(id) && nowMs - prior.infoAtMs >= INFO_RESEND_MS)
            val t = if (describe) {
                if (view.card != null) cards[id] = view.card else cards.remove(id)
                send(info)
                // A display the client (re)creates waits for its timeline, as after a server's description
                Told(info, nowMs).also { told[id] = it }
            } else {
                prior
            }
            val forced = syncWanted.remove(id) || describe
            if (view.card != null || info.url.isEmpty()) continue
            val sample = view.sample ?: continue
            syncIfDue(id, t, sample, info, clock, nowMs, forced, oneWayLatencyMs)
        }
    }

    private fun syncIfDue(
        id: UUID, t: Told, sample: ReplaySample, info: DisplayInfo, clock: Clock, nowMs: Long, forced: Boolean,
        oneWayLatencyMs: Double,
    ) {
        val paused = sample.paused || clock.held || clock.speed <= 0.0
        val rate = if (paused) PlaybackSpeed.clamp(sample.rate) else PlaybackSpeed.clamp(sample.rate * clock.speed)
        val position = sample.positionAt(clock.replayMs)
        val last = t.timeline
        val due = when {
            forced || last == null || last.mediaGen != sample.mediaGen || last.paused != paused -> true
            abs(last.rate - rate) > RATE_EPSILON -> true
            nowMs - last.atMs < MIN_INTERVAL_MS -> false
            paused -> abs(position - last.positionMs) > PAUSED_MOVE_MS
            else -> nowMs - last.atMs >= KEEPALIVE_MS || abs(position - (t.expectedMs(nowMs) ?: position)) > DRIFT_MS * rate
        }
        if (!due) return
        // The follower adds its one-way latency to a playing timeline: take it off, so it lands on the replay position
        val sent = if (paused) position else (position - (oneWayLatencyMs * rate).roundToLong()).coerceAtLeast(0)
        lastStamp = maxOf(lastStamp + 1, nowMs)
        send(
            DisplaySync(
                id = id, isSync = true, isPaused = paused, currentTimeMs = sent, durationMs = sample.durationMs,
                serverTimeMs = lastStamp, loop = sample.loop, mode = info.mode, rate = rate, mediaGen = sample.mediaGen,
            )
        )
        t.timeline = Told.Timeline(paused, rate, position, nowMs, sample.mediaGen)
    }

    /** The description as the replay shows it: nothing that would act on its own (a scheduled start), no media for cards. */
    private fun shown(view: ReplayView): DisplayInfo = view.info.copy(
        url = if (view.card != null) "" else view.info.url,
        scheduledStartEpochMillis = 0,
        scheduledAction = -1,
        isLocked = true,
    )

    /** Equal descriptions (their custom data compared by content, which data-class equality does not do for arrays). */
    private fun sameInfo(a: DisplayInfo, b: DisplayInfo): Boolean =
        a.copy(customData = emptyMap()) == b.copy(customData = emptyMap()) &&
            a.customData.keys == b.customData.keys &&
            a.customData.all { (k, v) -> b.customData[k]?.contentEquals(v) == true }

    fun reset() {
        told.clear()
        syncWanted.clear()
        infoWanted.clear()
        cards.clear()
    }

    companion object {
        private const val INFO_RESEND_MS = 2_000L
        private const val MIN_INTERVAL_MS = 200L
        private const val KEEPALIVE_MS = 1_000L
        private const val DRIFT_MS = 250.0
        private const val PAUSED_MOVE_MS = 40L
        private const val RATE_EPSILON = 0.005
    }
}
