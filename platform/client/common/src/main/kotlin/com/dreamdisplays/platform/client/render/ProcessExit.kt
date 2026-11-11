package com.dreamdisplays.platform.client.render

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/** Bounded wait for a helper process. */
internal object ProcessExit {
    /**
     * Waits up to [timeoutMs] for [proc] to exit. On timeout kills it, so its stdout / stderr readers reach end of
     * stream, and returns false. Cancelling the caller interrupts the wait.
     */
    suspend fun awaitOrKill(proc: Process, timeoutMs: Long): Boolean {
        val exited = runInterruptible(Dispatchers.IO) { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
        if (!exited) proc.destroyForcibly()
        return exited
    }
}
