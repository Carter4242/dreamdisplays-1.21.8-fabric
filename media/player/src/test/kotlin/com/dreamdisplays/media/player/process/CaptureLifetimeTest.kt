package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.win.WinApi
import com.dreamdisplays.util.OsInfo
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureLifetimeTest {
    @Test
    fun `Windows starts the command as is, elsewhere under the stdin watcher`() {
        val args = listOf("ffmpeg", "-i", "x")
        assertEquals(args, CaptureLifetime.command(args, windows = true))
        assertEquals(listOf("/bin/sh", "-c", CaptureLifetime.WATCH_STDIN_SCRIPT, "sh") + args, CaptureLifetime.command(args, windows = false))
    }

    @Test
    fun `a process in the kill-on-close job dies when the job's last handle closes`() {
        if (!OsInfo.isWindows) return
        val job = assertNotNull(CaptureLifetime.createKillOnCloseJob())
        val child = ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 >NUL").start()
        try {
            assertTrue(CaptureLifetime.assign(job, child.pid()))
            // Closing the only handle is what Windows does when the JVM dies
            WinApi.kernel32.CloseHandle(job)
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "the job's process survived its job")
        } finally {
            child.toHandle().descendants().forEach { it.destroyForcibly() }
            child.destroyForcibly()
        }
    }

    @Test
    fun `the watcher kills the command at end of stdin and passes its exit status through`() {
        if (OsInfo.isWindows) return
        val long = ProcessBuilder(CaptureLifetime.command(listOf("sleep", "60"))).start()
        try {
            Thread.sleep(300)
            assertTrue(long.isAlive)
            // What the kernel does to the pipe when the JVM dies
            long.outputStream.close()
            assertTrue(long.waitFor(5, TimeUnit.SECONDS), "the command outlived the end of its stdin")
            assertTrue(long.toHandle().descendants().noneMatch { it.isAlive })
        } finally {
            long.toHandle().descendants().forEach { it.destroyForcibly() }
            long.destroyForcibly()
        }
        val short = ProcessBuilder(CaptureLifetime.command(listOf("sh", "-c", "exit 7"))).start()
        assertTrue(short.waitFor(5, TimeUnit.SECONDS))
        assertEquals(7, short.exitValue())
    }
}
