package com.dreamdisplays.core.queue

import com.dreamdisplays.core.protocol.common.packets.QueueActionType
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Per-player limits on queue packets: far above a menu's use, a flood is cut to a trickle and one chat line. */
class QueueActionLimitsTest {
    private val alice = UUID(0, 1)
    private val bob = UUID(0, 2)

    /** [perSecond] packets of [type] from [player] for [seconds]; how many were let through. */
    private fun flood(limits: QueueActionLimits, player: UUID, type: QueueActionType, perSecond: Int, seconds: Int, startMs: Long = 0): Int {
        var passed = 0
        val total = perSecond * seconds
        for (i in 0 until total) {
            if (limits.tryAction(player, type, startMs + i * 1000L / perSecond)) passed++
        }
        return passed
    }

    @Test
    fun `a menu's clicks all go through`() {
        val limits = QueueActionLimits()
        // A burst of ten quick clicks, then one every 300 ms for a minute
        repeat(10) { assertTrue(limits.tryAction(alice, QueueActionType.REMOVE, 0)) }
        for (t in 2_000L until 62_000L step 300) assertTrue(limits.tryAction(alice, QueueActionType.ADD, t), "click at $t")
        // Two playlists right after each other
        assertTrue(limits.tryAction(bob, QueueActionType.ADD_BATCH, 0))
        assertTrue(limits.tryAction(bob, QueueActionType.ADD_BATCH, 100))
    }

    @Test
    fun `a batch flood just under the kick threshold is cut to one a second`() {
        val passed = flood(QueueActionLimits(), alice, QueueActionType.ADD_BATCH, perSecond = 490, seconds = 10)
        // Burst of two, then one a second
        assertTrue(passed in 10..13, "passed $passed of 4900")
    }

    @Test
    fun `any queue packet flood is cut to the sustained rate`() {
        for (type in QueueActionType.entries.filter { it != QueueActionType.ADD_BATCH }) {
            val passed = flood(QueueActionLimits(), alice, type, perSecond = 490, seconds = 10)
            assertTrue(passed in 50..61, "$type passed $passed of 4900")
        }
    }

    @Test
    fun `one player's flood leaves everyone else alone`() {
        val limits = QueueActionLimits()
        flood(limits, alice, QueueActionType.ADD_BATCH, perSecond = 490, seconds = 2)
        assertFalse(limits.tryAction(alice, QueueActionType.ADD_BATCH, 2_000))
        assertTrue(limits.tryAction(bob, QueueActionType.ADD_BATCH, 2_000))
        assertTrue(limits.tryAction(bob, QueueActionType.ADD, 2_000))
    }

    @Test
    fun `a refused flood says so in chat once every few seconds`() {
        val limits = QueueActionLimits()
        var told = 0
        for (i in 0 until 4_900) if (limits.tryRefusalMessage(alice, i * 1000L / 490)) told++
        // Ten seconds: the first one, then one every three seconds
        assertEquals(4, told)
        assertTrue(limits.tryRefusalMessage(bob, 0), "another player's refusal is still told")
    }

    @Test
    fun `forgetting a player gives them a fresh bucket`() {
        val limits = QueueActionLimits()
        flood(limits, alice, QueueActionType.ADD_BATCH, perSecond = 100, seconds = 1)
        assertFalse(limits.tryAction(alice, QueueActionType.ADD_BATCH, 1_000))
        limits.forget(alice)
        assertTrue(limits.tryAction(alice, QueueActionType.ADD_BATCH, 1_000))
    }

    @Test
    fun `buckets stay bounded and a clock going back refills nothing`() {
        val buckets = TokenBuckets(capacity = 2.0, refillPerSecond = 1.0, maxKeys = 100)
        for (i in 0 until 1_000) buckets.tryTake(UUID(1, i.toLong()), nowMs = i * 10_000L)
        assertTrue(buckets.size() <= 100, "size ${buckets.size()}")

        val b = TokenBuckets(capacity = 2.0, refillPerSecond = 1.0)
        assertTrue(b.tryTake(alice, 10_000))
        assertTrue(b.tryTake(alice, 10_000))
        assertFalse(b.tryTake(alice, 5_000))
        assertFalse(b.tryTake(alice, 10_500))
        assertTrue(b.tryTake(alice, 11_000))
    }
}
