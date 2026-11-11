package com.dreamdisplays.platform.client.storage

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CoalescingWriterTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    @AfterTest
    fun shutdown() {
        executor.shutdownNow()
    }

    private fun settle() {
        // Runs after anything already scheduled on the single thread
        executor.schedule({}, 200, TimeUnit.MILLISECONDS).get()
    }

    @Test
    fun burstOfRequestsWritesOnce() {
        val writes = AtomicInteger()
        val writer = CoalescingWriter(50, executor) { writes.incrementAndGet() }
        repeat(40) { writer.request() }
        settle()
        assertEquals(1, writes.get())
    }

    @Test
    fun flushWritesPendingChangeImmediatelyAndOnlyOnce() {
        val writes = AtomicInteger()
        val writer = CoalescingWriter(10_000, executor) { writes.incrementAndGet() }
        writer.request()
        writer.flush()
        assertEquals(1, writes.get())
        writer.flush()
        assertEquals(1, writes.get(), "nothing changed since the last write")
    }

    @Test
    fun requestAfterAWriteWritesAgain() {
        val writes = AtomicInteger()
        val writer = CoalescingWriter(20, executor) { writes.incrementAndGet() }
        writer.request()
        settle()
        writer.request()
        settle()
        assertEquals(2, writes.get())
    }
}
