package com.dreamdisplays.platform.client.render

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessExitTest {
    /** A process that exits after [exitAfterMs], or never when null. */
    private class FakeProcess(private val exitAfterMs: Long?) : Process() {
        var killed = false
        val waitedMs = mutableListOf<Long>()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = error("an unbounded wait is what hung scrub previews")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitedMs += unit.toMillis(timeout)
            return exitAfterMs != null && exitAfterMs <= unit.toMillis(timeout)
        }
        override fun exitValue(): Int = 0
        override fun destroy() { killed = true }
    }

    @Test
    fun hungProcessIsKilledAtTheTimeout() = runBlocking {
        val proc = FakeProcess(exitAfterMs = null)
        assertFalse(ProcessExit.awaitOrKill(proc, 25_000))
        assertEquals(listOf(25_000L), proc.waitedMs)
        assertTrue(proc.killed)
    }

    @Test
    fun processThatExitsInTimeIsLeftAlone() = runBlocking {
        val proc = FakeProcess(exitAfterMs = 10)
        assertTrue(ProcessExit.awaitOrKill(proc, 25_000))
        assertFalse(proc.killed)
    }
}
