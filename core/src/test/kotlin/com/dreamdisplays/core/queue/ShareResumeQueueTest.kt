package com.dreamdisplays.core.queue

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A screen share takes a looping queue's playing entry over for a while, then gives it back. */
class ShareResumeQueueTest {
    private val adder = UUID(0, 1)
    private fun item(id: String) = QueueItem(id, "https://youtu.be/$id", "en", "T $id", 60_000, "", adder, "Alice", 0)

    @Test
    fun `the interrupted entry is current again and a looping queue keeps it`() {
        val a = item("a")
        val queue = DisplayQueue(listOf(item("b"), item("c")), loop = true, current = a)
        // The share's relay URL replaced it
        queue.onVideoSetByHand("https://relay.example/live/ddshare")
        assertNull(queue.current)
        // The share ended and A is back
        assertTrue(queue.resumeCurrent(a))
        assertEquals(a, queue.current)
        queue.advance()
        assertEquals(listOf("c", "a"), queue.items.map { it.itemId })
    }

    @Test
    fun `nothing is put back over another playing entry`() {
        val b = item("b")
        val queue = DisplayQueue(emptyList(), current = b)
        assertFalse(queue.resumeCurrent(item("a")))
        assertEquals(b, queue.current)
    }
}
