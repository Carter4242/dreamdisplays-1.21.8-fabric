package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.Timeline
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells a viewer's seek on a server-timeline display apart from a client looping its own player at end of stream.
 * Older clients send a seek / restart to 0 when their stream ends, which may be a moment before the shared timeline
 * does; taken as a seek it would restart the video for everyone and keep the queue from moving on. The first such
 * report per end is [Verdict.STREAM_ENDED] (move the queue on, or let the timeline wrap by itself); every other one
 * around that end, from any client, is a [Verdict.DUPLICATE] to drop. Thread-safe.
 */
class EndOfStreamGuard(private val graceMs: Long = DEFAULT_GRACE_MS) {
    enum class Verdict { USER_SEEK, STREAM_ENDED, DUPLICATE }

    /** Per display: until when (wall ms) restart-looking seeks are end-of-stream reports to drop. */
    private val swallowUntil = ConcurrentHashMap<UUID, Long>()

    /** Classifies [action] to [targetMs] arriving at [nowMs] on [displayId], whose shared clock is [timeline]. */
    fun classify(
        displayId: UUID, timeline: Timeline?, nowMs: Long, action: PlaybackAction, targetMs: Long,
        durationMs: Long = timeline?.durationMs ?: 0L,
    ): Verdict {
        if (action != PlaybackAction.SEEK && action != PlaybackAction.RESTART) return Verdict.USER_SEEK
        val target = if (action == PlaybackAction.RESTART) 0L else targetMs
        if (target > QueueTiming.RESTART_TARGET_MAX_MS) return Verdict.USER_SEEK
        swallowUntil[displayId]?.let { if (nowMs <= it) return Verdict.DUPLICATE }
        if (timeline == null || !QueueTiming.isEndOfStreamLoop(timeline, nowMs, target, durationMs)) return Verdict.USER_SEEK
        val endAt = QueueTiming.switchAtMs(timeline, nowMs, QueueTiming.END_OF_STREAM_WINDOW_MS * 2, durationMs) ?: nowMs
        var first = false
        swallowUntil.compute(displayId) { _, old ->
            if (old != null && nowMs <= old) old else {
                first = true
                maxOf(endAt, nowMs) + graceMs
            }
        }
        return if (first) Verdict.STREAM_ENDED else Verdict.DUPLICATE
    }

    /** The video of [displayId] changed (or will start) at [startAtMs]: late end-of-stream reports of the old one are dropped. */
    fun onVideoChanged(displayId: UUID, startAtMs: Long) {
        swallowUntil.merge(displayId, startAtMs + graceMs, ::maxOf)
    }

    fun forget(displayId: UUID) {
        swallowUntil.remove(displayId)
    }

    /** Forgets every display. */
    fun clear() = swallowUntil.clear()

    companion object {
        const val DEFAULT_GRACE_MS = 3_000L

        /**
         * Whether a seek or restart arriving while the display is [paused] (or [holding], a start hold that is paused
         * only until every viewer is ready) plays once its own hold releases. A start hold is a playing display:
         * a seek during one must not turn it into a lasting pause.
         */
        fun playsAfterJump(action: PlaybackAction, paused: Boolean, holding: Boolean): Boolean =
            action == PlaybackAction.RESTART || !paused || holding
    }
}
