package com.dreamdisplays.platform.server.meta

import kotlinx.coroutines.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server-side coroutine scope for pure off-thread IO that never touches `Bukkit` / `Minecraft` state — webhooks, HTTP calls,
 * and similar.
 *
 * It runs on threads of its own, never on kotlinx's shared `Dispatchers.IO` / `Default`: those threads outlive the plugin
 * (nothing shuts them down on disable), so an unload would leave them holding its classloader. [shutdown] stops ours.
 */
object ServerCoroutines {
    private const val THREADS = 4
    private const val IDLE_SECONDS = 30L
    private const val SHUTDOWN_WAIT_MS = 2_000L

    private val threadIds = AtomicInteger()
    private val lock = Any()
    private var pool: ScheduledThreadPoolExecutor? = null
    private var scope: CoroutineScope? = null

    /**
     * The server-side coroutine scope for all background IO. After a [shutdown] the next use starts it again: in
     * single-player the next world starts another server in this same process and launches here again.
     */
    val io: CoroutineScope
        get() = synchronized(lock) { scope ?: start() }

    private fun start(): CoroutineScope {
        // Scheduled, so `delay` waits on these threads too rather than on kotlinx's own timer thread
        val executor = ScheduledThreadPoolExecutor(THREADS) { r ->
            Thread(r, "DreamDisplays Server-IO #${threadIds.incrementAndGet()}").apply { isDaemon = true }
        }.apply {
            setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            removeOnCancelPolicy = true
            // A shutdown drops pending delays (their coroutines are cancelled anyway) instead of waiting them out
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
        pool = executor
        return CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher() + CoroutineName("Server-IO"))
            .also { scope = it }
    }

    /**
     * Cancels all background server IO coroutines and stops their threads, waiting up to [SHUTDOWN_WAIT_MS] for work
     * already running (a write in progress). Called on server stop / plugin disable.
     */
    fun shutdown() {
        val (oldScope, executor) = synchronized(lock) {
            val pair = scope to pool
            scope = null
            pool = null
            pair
        }
        oldScope?.coroutineContext?.cancelChildren()
        if (executor == null) return
        executor.shutdown()
        runCatching { executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS) }
        executor.shutdownNow()
    }

    /**
     * Runs everything [shutdown] will, on a throwaway scope, so the coroutine classes it needs — the
     * scope's own, and the cancellation machinery, which nothing else here ever touches — are loaded
     * while the plugin classloader is fresh.
     */
    fun warmUp() {
        CoroutineScope(SupervisorJob()).apply { launch(start = CoroutineStart.LAZY) {} }.coroutineContext.cancelChildren()
    }
}
