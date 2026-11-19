package com.dreamdisplays.platform.client.displays

import java.util.concurrent.atomic.AtomicInteger

/**
 * The screen share's capture tests as a single background job: [request] starts [work] on its own thread unless it
 * is already running; a request while it runs makes it run once more right after (a screen plugged in meanwhile
 * gets tested). [running] from the request until the last round ends. [spawn] starts the thread (tests run it inline).
 */
internal class ShareChecks(
    private val work: () -> Unit,
    private val spawn: (Runnable) -> Unit = { body ->
        Thread(body, "DreamDisplays-screen-share-probe").apply { isDaemon = true }.start()
    },
) {
    private val state = AtomicInteger(IDLE)

    /** True while a round runs or is about to. */
    val running: Boolean get() = state.get() != IDLE

    /** Starts a round, or asks the running one for another after it. */
    fun request() {
        while (true) {
            when (state.get()) {
                IDLE -> if (state.compareAndSet(IDLE, RUNNING)) {
                    runCatching { spawn(Runnable(::loop)) }.onFailure { state.set(IDLE) }
                    return
                }

                RUNNING -> if (state.compareAndSet(RUNNING, AGAIN)) return
                else -> return
            }
        }
    }

    private fun loop() {
        while (true) {
            runCatching(work)
            if (state.compareAndSet(RUNNING, IDLE)) return
            // A request came in meanwhile: one more round
            state.set(RUNNING)
        }
    }

    private companion object {
        const val IDLE = 0
        const val RUNNING = 1
        const val AGAIN = 2
    }
}
