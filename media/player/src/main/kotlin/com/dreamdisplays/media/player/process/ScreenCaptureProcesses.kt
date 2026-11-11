package com.dreamdisplays.media.player.process

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Every screen-capture `FFmpeg` this JVM started, so none can outlive its share: [kill] ends one at once with its whole
 * process tree (`destroyForcibly`, no graceful quit), [reap] is the watchdog that kills any that should not run, and a
 * JVM shutdown hook kills whatever is left when the game quits.
 */
object ScreenCaptureProcesses {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Live capture processes. */
    private val tracked: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    init {
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({ killAll() }, "DreamDisplays-screen-capture-shutdown"))
        }.onFailure { logger.warn("Could not register the screen capture shutdown hook: {}", it.toString()) }
    }

    /** Starts tracking [proc]; call right after it started. */
    fun register(proc: Process) {
        tracked += proc
    }

    /** Capture processes started here that are still running. Cheap (no process-table walk): fine once per frame. */
    fun alive(): List<Process> {
        if (tracked.isEmpty()) return []
        tracked.removeIf { !it.isAlive }
        return tracked.toList()
    }

    /**
     * Kills [proc] and every process under it now (`destroyForcibly`, on the calling thread: no graceful quit, no
     * wait). The descendants are listed before the parent dies, as a dead parent loses them. Returns the handles that
     * were signalled, for [awaitGone].
     */
    fun kill(proc: Process): List<ProcessHandle> {
        val handles = buildList {
            runCatching { proc.toHandle().descendants().forEach { add(it) } }
            add(proc.toHandle())
        }
        // Children first: a parent that dies first may leave them reparented and harder to find
        for (h in handles) runCatching { h.destroyForcibly() }
        runCatching { proc.destroyForcibly() }
        return handles
    }

    /**
     * Waits up to [timeoutMs] for every one of [handles] to be gone, signalling stragglers again; true when all are
     * gone. Blocks: call off the render thread.
     */
    fun awaitGone(handles: List<ProcessHandle>, timeoutMs: Long = 2_000L): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val left = handles.filter { it.isAlive }
            if (left.isEmpty()) return true
            if (System.nanoTime() >= deadline) return false
            left.forEach { runCatching { it.destroyForcibly() } }
            runCatching { Thread.sleep(20L) }.onFailure { Thread.currentThread().interrupt(); return handles.none { it.isAlive } }
        }
    }

    /** [kill] and [awaitGone] in one, then forgets [proc]; true when the tree is gone. Blocks up to [timeoutMs]. */
    fun killAndVerify(proc: Process, timeoutMs: Long = 2_000L): Boolean {
        val gone = awaitGone(kill(proc), timeoutMs)
        if (gone) tracked -= proc
        else logger.error("Screen capture process {} is still running after a forced kill.", proc.pid())
        return gone
    }

    /**
     * The watchdog: kills every tracked capture that is not [current], and [current] too when [sharing] is false
     * (see `ScreenShareCaptureRules.isOrphan`). Returns how many were killed. Does not wait; cheap when none run.
     */
    fun reap(sharing: Boolean, current: Process?): Int {
        if (tracked.isEmpty()) return 0
        var killed = 0
        for (p in alive()) {
            val orphan = !sharing || p !== current
            if (!orphan) continue
            logger.warn("Killing a screen capture process that outlived its share (pid {}).", p.pid())
            kill(p)
            killed++
        }
        return killed
    }

    /** Kills every tracked capture now (game quitting, disconnect). Does not wait. */
    fun killAll() {
        for (p in tracked.toList()) kill(p)
    }
}
