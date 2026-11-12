package com.dreamdisplays.core.queue

import com.dreamdisplays.core.protocol.common.packets.QueueActionType

/**
 * Per-player limits on client queue packets, checked before anything else is done with one (range, permissions, URL
 * validation). A menu sends one packet per click and a playlist one batch, so these sit far above any real use; a
 * modified client flooding them gets dropped packets and, at most every [REFUSAL_MESSAGE_INTERVAL_MS], one chat line.
 */
class QueueActionLimits {
    private val actions = TokenBuckets(ACTION_BURST, ACTIONS_PER_SECOND)
    private val refusalMessages = TokenBuckets(1.0, 1000.0 / REFUSAL_MESSAGE_INTERVAL_MS)

    /** True when [playerId] may have a [type] queue packet handled at [nowMs]; spends its cost. */
    fun tryAction(playerId: Any, type: QueueActionType, nowMs: Long): Boolean = actions.tryTake(playerId, nowMs, costOf(type))

    /** True when [playerId] may be told about a refused queue action at [nowMs]; otherwise it goes unsaid. */
    fun tryRefusalMessage(playerId: Any, nowMs: Long): Boolean = refusalMessages.tryTake(playerId, nowMs)

    fun forget(playerId: Any) {
        actions.forget(playerId)
        refusalMessages.forget(playerId)
    }

    companion object {
        const val ACTION_BURST = 10.0
        const val ACTIONS_PER_SECOND = 5.0

        /** A batch carries up to [QueueAddGate.MAX_BATCH] URLs: two at once, then one a second. */
        const val BATCH_COST = 5.0
        const val REFUSAL_MESSAGE_INTERVAL_MS = 3_000L

        fun costOf(type: QueueActionType): Double = if (type == QueueActionType.ADD_BATCH) BATCH_COST else 1.0
    }
}
