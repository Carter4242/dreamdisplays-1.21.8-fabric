package com.dreamdisplays.core.queue

import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.shared.playback.PlaybackPermissions
import java.util.*

/**
 * The checks a client's queue add passes before any of its URLs is looked at. A batch (playlist) carries up to
 * [MAX_BATCH] URLs and validating them is the expensive part, so an add that is refused anyway, or a batch longer
 * than what still fits, must not pay for it.
 */
object QueueAddGate {
    /** Most entries one batch add looks at. */
    const val MAX_BATCH = 100

    enum class Refusal { DISABLED, NOT_RUNNING, NOT_ALLOWED }

    /** Why the actor in [ctx] may not add to a display's queue at all, or null when they may. */
    fun refusal(queueEnabled: Boolean, virtualDisplay: Boolean, ctx: PlaybackContext): Refusal? = when {
        !queueEnabled -> Refusal.DISABLED
        virtualDisplay || !PlaybackPermissions.queueRuns(ctx.mode) -> Refusal.NOT_RUNNING
        !PlaybackPermissions.canAddToQueue(ctx) -> Refusal.NOT_ALLOWED
        else -> null
    }

    /**
     * How many entries [actorId] can still add to [queue]: its free places and, unless [exempt] (owner / admin), what
     * is left of their own share; at most [MAX_BATCH]. Mirrors [DisplayQueue.add]'s limits.
     */
    fun room(queue: DisplayQueue?, actorId: UUID, limits: QueueLimits, exempt: Boolean): Int {
        var room = MAX_BATCH
        val size = queue?.size ?: 0
        if (limits.maxItems > 0) room = minOf(room, limits.maxItems - size)
        if (!exempt && limits.maxPerPlayer > 0) room = minOf(room, limits.maxPerPlayer - (queue?.countBy(actorId) ?: 0))
        return room.coerceAtLeast(0)
    }

    /**
     * The part of a client's batch worth validating: nothing when [refusal] is set, otherwise the first [room] items,
     * at least one so a full queue still answers with why (full, or the player's share used up).
     */
    fun <T> toValidate(items: List<T>, refusal: Refusal?, room: Int): List<T> {
        if (refusal != null || items.isEmpty()) return emptyList()
        return items.subList(0, minOf(items.size, room.coerceIn(1, MAX_BATCH)))
    }
}
