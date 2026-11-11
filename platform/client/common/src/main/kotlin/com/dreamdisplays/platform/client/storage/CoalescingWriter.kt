package com.dreamdisplays.platform.client.storage

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collapses bursts of save requests (a slider drag persists on every step) into one [write] at most every
 * [delayMs], run on [executor] instead of the caller's thread. [flush] writes any pending change at once.
 * Writes never overlap.
 */
internal class CoalescingWriter(
    private val delayMs: Long,
    private val executor: ScheduledExecutorService,
    private val write: () -> Unit,
) {
    private val dirty = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val writeLock = Any()

    /** Marks the data changed; a write follows within [delayMs]. */
    fun request() {
        dirty.set(true)
        if (scheduled.compareAndSet(false, true)) {
            executor.schedule({
                scheduled.set(false)
                drain()
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Writes now (on the caller's thread) if anything changed since the last write. */
    fun flush() = drain()

    private fun drain() = synchronized(writeLock) {
        if (dirty.getAndSet(false)) write()
    }
}
