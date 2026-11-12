package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.shared.policy.CustomMediaPolicy
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A client's queue add is refused, or cut to what can still go in, before any of its URLs is validated. */
class QueueAddGateTest {
    private val alice = UUID(0, 1)
    private val bob = UUID(0, 2)
    private val limits = QueueLimits(maxItems = 50, maxPerPlayer = 10)

    private fun ctx(mode: PlaybackMode = PlaybackMode.SYNCED, owner: Boolean = false, admin: Boolean = false, locked: Boolean = false) =
        PlaybackContext(mode, isOwner = owner, isAdmin = admin, isLocked = locked)

    private fun item(n: Int, by: UUID) = QueueItem("i$n", "https://youtu.be/v$n", "en", "T$n", 60_000, "", by, "x", 0)

    private fun queueOf(vararg adders: UUID) = DisplayQueue(adders.mapIndexed { i, by -> item(i, by) })

    @Test
    fun `refusals match what addAll refused with`() {
        assertEquals(QueueAddGate.Refusal.DISABLED, QueueAddGate.refusal(false, false, ctx(owner = true)))
        assertEquals(QueueAddGate.Refusal.NOT_RUNNING, QueueAddGate.refusal(true, true, ctx(owner = true)))
        assertEquals(QueueAddGate.Refusal.NOT_RUNNING, QueueAddGate.refusal(true, false, ctx(PlaybackMode.LOCAL, owner = true)))
        // A locked display: only its owner or an admin may add
        assertEquals(QueueAddGate.Refusal.NOT_ALLOWED, QueueAddGate.refusal(true, false, ctx(locked = true)))
        assertNull(QueueAddGate.refusal(true, false, ctx(locked = true, owner = true)))
        assertNull(QueueAddGate.refusal(true, false, ctx(locked = true, admin = true)))
        assertNull(QueueAddGate.refusal(true, false, ctx()))
        // Broadcast: owner or admin only, even unlocked
        assertEquals(QueueAddGate.Refusal.NOT_ALLOWED, QueueAddGate.refusal(true, false, ctx(PlaybackMode.BROADCAST)))
        assertNull(QueueAddGate.refusal(true, false, ctx(PlaybackMode.BROADCAST, owner = true)))
    }

    @Test
    fun `room is the queue's free places and the player's own share`() {
        assertEquals(10, QueueAddGate.room(null, alice, limits, exempt = false))
        assertEquals(50, QueueAddGate.room(null, alice, limits, exempt = true))
        assertEquals(QueueAddGate.MAX_BATCH, QueueAddGate.room(null, alice, QueueLimits(0, 0), exempt = false))
        val queue = queueOf(*Array(7) { alice }, *Array(40) { bob })
        assertEquals(3, QueueAddGate.room(queue, alice, limits, exempt = false))
        assertEquals(0, QueueAddGate.room(queue, bob, limits, exempt = false))
        assertEquals(3, QueueAddGate.room(queue, bob, limits, exempt = true), "the owner is held to the queue's size only")
    }

    @Test
    fun `room agrees with what DisplayQueue add takes`() {
        for (own in 0..12) for (others in listOf(0, 30, 45, 50)) for (exempt in listOf(false, true)) {
            val queue = queueOf(*Array(minOf(own, 50 - others).coerceAtLeast(0)) { alice }, *Array(others) { bob })
            val room = QueueAddGate.room(queue, alice, limits, exempt)
            var added = 0
            for (n in 0 until 200) {
                if (queue.add(item(1000 + n, alice), limits, exempt) != QueueAddResult.ADDED) break
                added++
            }
            assertEquals(added, room, "own=$own others=$others exempt=$exempt")
        }
    }

    @Test
    fun `only what can go in is validated, and nothing when refused`() {
        val batch = List(100) { it }
        assertEquals(emptyList(), QueueAddGate.toValidate(batch, QueueAddGate.Refusal.NOT_ALLOWED, room = 50))
        assertEquals(emptyList(), QueueAddGate.toValidate(batch, QueueAddGate.Refusal.DISABLED, room = 50))
        assertEquals(List(10) { it }, QueueAddGate.toValidate(batch, null, room = 10))
        // A full queue still looks at one, so the player hears why
        assertEquals(listOf(0), QueueAddGate.toValidate(batch, null, room = 0))
        assertEquals(List(100) { it }, QueueAddGate.toValidate(List(500) { it }, null, room = 1_000))
        assertEquals(emptyList(), QueueAddGate.toValidate(emptyList<Int>(), null, room = 10))
    }

    /**
     * The audit's flood: 100 long URLs per packet. Validating them (what every packet used to cost, permission or not)
     * against the gate's refusal, which looks at none; the timings are printed, the bound on the refused path is loose.
     */
    @Test
    fun `a refused batch costs next to nothing`() {
        val batch = List(100) { i ->
            QueueItemWire(url = "https://example.com/" + "a".repeat(250) + "/video$i.mp4?x=" + "b".repeat(30), lang = "en", title = "t".repeat(100))
        }
        val settings = CustomMediaPolicy.Settings()
        var validated = 0
        fun validate(items: List<QueueItemWire>) = items.count { item ->
            validated++
            val url = item.url.trim()
            url.isNotEmpty() && MediaUrlPolicy.isAllowed(url) && CustomMediaPolicy.evaluate(url, settings) == CustomMediaPolicy.Verdict.ALLOWED
        }
        val locked = ctx(locked = true)

        repeat(200) { validate(batch) } // warm-up
        val fullRounds = 500
        val fullStart = System.nanoTime()
        repeat(fullRounds) { validate(batch) }
        val fullNanos = (System.nanoTime() - fullStart) / fullRounds

        validated = 0
        val refusedRounds = 200_000
        repeat(20_000) { validate(QueueAddGate.toValidate(batch, QueueAddGate.refusal(true, false, locked), 50)) }
        val refusedStart = System.nanoTime()
        repeat(refusedRounds) { validate(QueueAddGate.toValidate(batch, QueueAddGate.refusal(true, false, locked), 50)) }
        val refusedNanos = (System.nanoTime() - refusedStart) / refusedRounds

        println("Batch of 100 URLs: validated ${fullNanos / 1000.0} us/packet, refused by the gate ${refusedNanos / 1000.0} us/packet")
        assertEquals(0, validated, "a refused batch validates no URL")
        assertTrue(refusedNanos < 20_000, "refused batch took $refusedNanos ns")
        assertTrue(refusedNanos * 10 < fullNanos, "refused $refusedNanos ns vs validated $fullNanos ns")
    }
}
