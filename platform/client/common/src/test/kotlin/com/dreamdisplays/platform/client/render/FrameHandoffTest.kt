package com.dreamdisplays.platform.client.render

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameHandoffTest {
    private fun frame(fill: Int, size: Int = 16): ByteBuffer =
        ByteBuffer.allocateDirect(size).also { b -> repeat(size) { b.put(fill.toByte()) }; b.flip() }

    @Test
    fun borrowedFrameIsNeverOverwrittenByLaterFrames() {
        val handoff = FrameHandoff<String>()
        handoff.publish(frame(1), 16, "a")
        val held = assertNotNull(handoff.borrowNewest(0))
        // The decoder keeps delivering while the render thread is still uploading the borrowed frame
        repeat(10) { i -> assertTrue(handoff.publish(frame(2 + i), 16, "b$i")) }
        assertEquals(0, held.buffer.position())
        assertEquals(16, held.buffer.remaining())
        repeat(16) { assertEquals(1.toByte(), held.buffer.get(it)) }
        assertEquals("a", held.meta)
        handoff.release()
    }

    @Test
    fun descriptionTravelsWithItsPixels() {
        val handoff = FrameHandoff<Pair<Int, Int>>()
        handoff.publish(frame(7, 8), 8, 2 to 4)
        val f = assertNotNull(handoff.borrowNewest(0))
        assertEquals(2 to 4, f.meta)
        assertEquals(8, f.buffer.remaining())
        handoff.release()
    }

    @Test
    fun sourceBufferPositionIsUntouched() {
        val handoff = FrameHandoff<String>()
        val src = frame(3, 32)
        src.position(4)
        assertTrue(handoff.publish(src, 16, "x"))
        assertEquals(4, src.position())
        assertEquals(32, src.limit())
    }

    @Test
    fun shortSourceIsRejected() {
        val handoff = FrameHandoff<String>()
        assertFalse(handoff.publish(frame(1, 8), 16, "x"))
        assertNull(handoff.borrowNewest(0))
    }

    @Test
    fun seenVersionIsNotBorrowedAgain() {
        val handoff = FrameHandoff<String>()
        handoff.publish(frame(1), 16, "a")
        val f = assertNotNull(handoff.borrowNewest(0))
        handoff.release()
        assertNull(handoff.borrowNewest(f.version))
        handoff.publish(frame(2), 16, "b")
        assertEquals("b", handoff.borrowNewest(f.version)?.meta)
        handoff.release()
    }

    @Test
    fun producerNeverWritesTheNewestOrBorrowedBuffer() {
        val handoff = FrameHandoff<Int>()
        handoff.publish(frame(1), 16, 1)
        val borrowed = assertNotNull(handoff.borrowNewest(0))
        handoff.publish(frame(2), 16, 2)
        val newest = assertNotNull(handoff.borrowNewest(borrowed.version))
        // Borrowing again moved the borrow to the newest; the earlier one is free again
        assertEquals(2, newest.meta)
        handoff.release()
        handoff.publish(frame(3), 16, 3)
        val third = assertNotNull(handoff.borrowNewest(newest.version))
        assertEquals(3, third.meta)
        repeat(16) { assertEquals(3.toByte(), third.buffer.get(it)) }
        handoff.release()
    }

    @Test
    fun clearDropsEverythingPublished() {
        val handoff = FrameHandoff<String>()
        handoff.publish(frame(1), 16, "a")
        handoff.clear()
        assertNull(handoff.borrowNewest(0))
        assertNull(handoff.latestMeta)
        handoff.publish(frame(2), 16, "b")
        assertEquals("b", handoff.borrowNewest(0)?.meta)
        handoff.release()
    }

    @Test
    fun clearWhileBorrowedKeepsTheBorrowedFrameIntact() {
        val handoff = FrameHandoff<String>()
        handoff.publish(frame(5), 16, "a")
        val held = assertNotNull(handoff.borrowNewest(0))
        handoff.clear()
        handoff.publish(frame(6), 16, "b")
        handoff.publish(frame(7), 16, "c")
        repeat(16) { assertEquals(5.toByte(), held.buffer.get(it)) }
        handoff.release()
        val next = assertNotNull(handoff.borrowNewest(held.version))
        assertEquals("c", next.meta)
        repeat(16) { assertEquals(7.toByte(), next.buffer.get(it)) }
        handoff.release()
    }
}
