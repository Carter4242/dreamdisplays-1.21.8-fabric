package com.dreamdisplays.platform.server.karaoke

import com.dreamdisplays.core.karaoke.EmitWait
import com.dreamdisplays.core.karaoke.KaraokeTiming
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One thread that runs timed sends at their `System.nanoTime()` deadline: it sleeps until shortly before a deadline,
 * spins only the last fraction of a millisecond, and runs everything due within a millisecond in that wake
 * ([EmitWait]).
 *
 * A task returns null when done, or a later deadline to run again then (its target moved).
 */
@PaperOnly
class VoiceEmitter : Thread("DreamDisplays Karaoke") {
    fun interface Task {
        fun run(nowNanos: Long): Long?
    }

    private class Entry(var atNanos: Long, val order: Long, val task: Task)

    private val logger = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = PriorityQueue<Entry>(compareBy<Entry> { it.atNanos }.thenBy { it.order })
    private var nextOrder = 0L

    @Volatile
    private var running = true

    /** Tasks dropped because the queue was full or they were due implausibly far ahead ([KaraokeTiming.emitTooFar]). */
    @Volatile
    var dropped: Long = 0
        private set

    init {
        isDaemon = true
        priority = MAX_PRIORITY
    }

    /** Runs [task] at [atNanos] (at once when that has passed); dropped when that is implausibly far ahead. */
    fun schedule(atNanos: Long, task: Task) {
        lock.withLock {
            if (queue.size >= MAX_QUEUED || KaraokeTiming.emitTooFar(atNanos, System.nanoTime())) {
                dropped++
                return
            }
            val entry = Entry(atNanos, nextOrder++, task)
            queue.add(entry)
            if (queue.peek() === entry) changed.signal()
        }
    }

    fun shutdown() {
        running = false
        lock.withLock {
            queue.clear()
            changed.signal()
        }
        interrupt()
    }

    override fun run() {
        while (running) {
            val entry = try {
                next() ?: continue
            } catch (_: InterruptedException) {
                continue
            }
            val again = try {
                entry.task.run(System.nanoTime())
            } catch (e: Exception) {
                logger.warn("Karaoke voice send failed.", e)
                null
            }
            if (again != null) {
                if (KaraokeTiming.emitTooFar(again, System.nanoTime())) {
                    lock.withLock { dropped++ }
                    continue
                }
                lock.withLock {
                    entry.atNanos = again
                    queue.add(entry)
                }
            }
        }
    }

    /** Waits for the earliest entry's deadline and takes it; null when woken early (a new earliest entry, stop). */
    private fun next(): Entry? {
        lock.withLock {
            val head = queue.peek()
            if (head == null) {
                changed.await()
                return null
            }
            val sleep = EmitWait.sleepNanos(head.atNanos, System.nanoTime())
            if (sleep > 0L) {
                changed.awaitNanos(sleep)
                return null
            }
        }
        // Spin out the last stretch without holding the lock, then take whatever is earliest now
        while (running) {
            val head = lock.withLock { queue.peek() } ?: return null
            if (EmitWait.due(head.atNanos, System.nanoTime())) break
            Thread.onSpinWait()
        }
        return lock.withLock {
            val head = queue.peek()
            if (head != null && EmitWait.due(head.atNanos, System.nanoTime())) queue.poll() else null
        }
    }

    private companion object {
        const val MAX_QUEUED = 50_000
    }
}
