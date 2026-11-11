package com.dreamdisplays.platform.server.meta

import kotlinx.coroutines.*

/**
 * Server-side coroutine scope for pure off-thread IO that never touches `Bukkit` / `Minecraft` state — webhooks, HTTP calls,
 * and similar.
 */
object ServerCoroutines {
    /** The server-side coroutine scope for all background IO. */
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("Server-IO"))

    /**
     * Cancels all background server IO coroutines. Called on server stop / plugin disable. The scope itself stays
     * usable: in single-player the next world starts another server in this same process and launches here again.
     */
    fun shutdown() {
        io.coroutineContext.cancelChildren()
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
