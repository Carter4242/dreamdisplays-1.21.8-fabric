package com.dreamdisplays.media.player.process

import com.dreamdisplays.util.OsInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real processes: a shell with a sleeping child stands in for a capture `FFmpeg` and anything it might start. */
class ScreenCaptureProcessesTest {
    private val started = ArrayList<Process>()

    private fun sleeperTree(): Process {
        // A shell that waits on a child (no exec shortcut), so there is a process tree to kill. Java starts it
        // without a console window.
        val p = if (OsInfo.isWindows) ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 >NUL").start()
        else ProcessBuilder("sh", "-c", "sleep 60; true").start()
        started += p
        ScreenCaptureProcesses.register(p)
        return p
    }

    private fun childrenOf(p: Process): List<ProcessHandle> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val kids = p.toHandle().descendants().toList()
            if (kids.isNotEmpty()) return kids
            Thread.sleep(20L)
        }
        return emptyList()
    }

    @AfterTest
    fun cleanUp() {
        started.forEach { ScreenCaptureProcesses.killAndVerify(it) }
    }

    @Test
    fun `kill ends the whole process tree at once`() {
        val p = sleeperTree()
        val children = childrenOf(p)
        assertTrue(children.isNotEmpty(), "the shell never started its child")
        val handles = ScreenCaptureProcesses.kill(p)
        assertTrue(children.all { it in handles }, "children are signalled too")
        assertTrue(ScreenCaptureProcesses.awaitGone(handles, 5_000L), "tree still alive after a forced kill")
        assertFalse(p.isAlive)
        assertTrue(children.none { it.isAlive })
        assertTrue(ScreenCaptureProcesses.alive().none { it === p })
    }

    @Test
    fun `killAndVerify confirms the tree is gone`() {
        val p = sleeperTree()
        val children = childrenOf(p)
        assertTrue(ScreenCaptureProcesses.killAndVerify(p, 5_000L))
        assertFalse(p.isAlive)
        assertTrue(children.none { it.isAlive })
    }

    @Test
    fun `watchdog kills every capture that is not the current share's`() {
        val current = sleeperTree()
        val stray = sleeperTree()
        assertEquals(1, ScreenCaptureProcesses.reap(sharing = true, current = current))
        assertTrue(ScreenCaptureProcesses.awaitGone(listOf(stray.toHandle()), 5_000L))
        assertTrue(current.isAlive, "the running share's own capture is left alone")
        assertEquals(0, ScreenCaptureProcesses.reap(sharing = true, current = current))

        // The client no longer believes it shares: its capture goes too
        assertEquals(1, ScreenCaptureProcesses.reap(sharing = false, current = current))
        assertTrue(ScreenCaptureProcesses.awaitGone(listOf(current.toHandle()), 5_000L))
        assertTrue(ScreenCaptureProcesses.alive().isEmpty())
    }

    @Test
    fun `killAll leaves nothing running`() {
        val a = sleeperTree()
        val b = sleeperTree()
        val kids = childrenOf(a) + childrenOf(b)
        ScreenCaptureProcesses.killAll()
        assertTrue(ScreenCaptureProcesses.awaitGone(listOf(a.toHandle(), b.toHandle()) + kids, 5_000L))
    }
}
