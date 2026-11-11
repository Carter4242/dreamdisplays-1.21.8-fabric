package com.dreamdisplays.media.runtime.system

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessesCollectorTest {
    /** [size] bytes of 'x', counting how many were read. */
    private class Endless(val size: Long) : InputStream() {
        var read = 0L
        override fun read(): Int = if (read < size) { read++; 'x'.code } else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= size) return -1
            val n = minOf(len.toLong(), size - read).toInt()
            b.fill('x'.code.toByte(), off, off + n)
            read += n
            return n
        }
    }

    @Test
    fun `output past the cap is drained but not kept`() {
        val input = Endless(5_000_000)
        val sink = StringBuilder()
        Processes.collector(input, sink, "t", maxChars = 1_000).apply { start(); join(10_000) }
        assertEquals(1_000, sink.length)
        assertEquals(5_000_000, input.read) // read to the end, so the child never blocks on a full pipe
    }

    @Test
    fun `output under the cap is kept whole`() {
        val sink = StringBuilder()
        Processes.collector("hello".byteInputStream(), sink, "t").apply { start(); join(10_000) }
        assertEquals("hello", sink.toString())
    }
}
