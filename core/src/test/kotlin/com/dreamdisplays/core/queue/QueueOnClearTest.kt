package com.dreamdisplays.core.queue

import com.dreamdisplays.core.queue.QueueOnClear.Outcome.BLANK
import com.dreamdisplays.core.queue.QueueOnClear.Outcome.NEXT_ENTRY
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Clearing a display's video moves its queue on like a skip; only with nothing waiting does it go blank. */
class QueueOnClearTest {
    private val adder = UUID(0, 1)
    private fun item(id: String) = QueueItem(id, "https://youtu.be/$id", "en", "T $id", 60_000, "", adder, "Alice", 0)

    @Test
    fun `no queue or an empty one goes blank`() {
        assertEquals(BLANK, QueueOnClear.outcome(queueRuns = true, queue = null))
        assertEquals(BLANK, QueueOnClear.outcome(queueRuns = true, queue = DisplayQueue()))
        // The playing entry alone is nothing waiting
        assertEquals(BLANK, QueueOnClear.outcome(queueRuns = true, queue = DisplayQueue(current = item("a"))))
    }

    @Test
    fun `an entry waiting is started like a skip`() {
        val queue = DisplayQueue(listOf(item("b"), item("c")), current = item("a"))
        assertEquals(NEXT_ENTRY, QueueOnClear.outcome(queueRuns = true, queue = queue))
        assertEquals("b", queue.advance()?.itemId)
        assertEquals(listOf("c"), queue.items.map { it.itemId }, "the cleared entry is gone without loop")
    }

    @Test
    fun `a display the queue does not run on goes blank whatever is queued`() {
        // Local mode, a virtual display, or queues off in the config
        assertEquals(BLANK, QueueOnClear.outcome(queueRuns = false, queue = DisplayQueue(listOf(item("b")))))
    }

    @Test
    fun `with loop on the cleared entry goes back to the end, as on a skip`() {
        val queue = DisplayQueue(listOf(item("b")), loop = true, current = item("a"))
        assertEquals(NEXT_ENTRY, QueueOnClear.outcome(queueRuns = true, queue = queue))
        assertEquals("b", queue.advance()?.itemId)
        assertEquals(listOf("a"), queue.items.map { it.itemId })
        // A one-entry loop playing its entry has nothing waiting: blank, where a skip does nothing
        val single = DisplayQueue(loop = true, current = item("a"))
        assertEquals(BLANK, QueueOnClear.outcome(queueRuns = true, queue = single))
    }

    @Test
    fun `a running share ends and the next entry plays, the share's interrupted entry not put back`() {
        val queue = DisplayQueue(listOf(item("b")), loop = true, current = item("a"))
        // The share's relay URL replaced A, then the clear ended the share without a restore
        queue.onVideoSetByHand("https://relay.example/live/ddshare")
        assertNull(queue.current)
        assertEquals(NEXT_ENTRY, QueueOnClear.outcome(queueRuns = true, queue = queue))
        assertEquals("b", queue.advance()?.itemId)
        assertEquals(emptyList(), queue.items.map { it.itemId })
        // A share on a display with nothing queued: it ends and the display goes blank
        val empty = DisplayQueue()
        empty.onVideoSetByHand("https://relay.example/live/ddshare")
        assertEquals(BLANK, QueueOnClear.outcome(queueRuns = true, queue = empty))
    }
}
