package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.win.WinApi
import com.dreamdisplays.util.OsInfo
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

/**
 * Ties every capture `FFmpeg` to this game's process, so a capture dies with the game even when the JVM dies without
 * running a shutdown hook (native crash, "Kill" in a launcher, Task Manager, `kill -9`): `FFmpeg` itself never notices
 * its parent is gone.
 *
 * - Windows: each process joins one Job Object created with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. Only this JVM holds
 *   the job's handle (not inheritable), so when the JVM ends, however it ends, Windows closes it and kills the job.
 * - Linux / macOS: the publisher runs under `sh` ([command]), which kills it once its stdin (a pipe whose only writer
 *   is this JVM) reaches end of file.
 */
object CaptureLifetime {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The `sh` script behind [command]: starts the real command (`"$@"`) with stdin from `/dev/null`, a watcher that
     * reads the script's own stdin (kept as fd 3: an asynchronous list's stdin is `/dev/null` otherwise) until end of
     * file and then kills the command, and exits with the command's status (killing the watcher when it ends first).
     */
    internal const val WATCH_STDIN_SCRIPT =
        "exec 3<&0; \"\$@\" </dev/null 3<&- & p=\$!; " +
            "( while read -r _; do :; done <&3; kill -9 \"\$p\" 2>/dev/null ) & w=\$!; exec 3<&-; " +
            "wait \"\$p\"; c=\$?; kill \"\$w\" 2>/dev/null; exit \"\$c\""

    /**
     * The argv to start [args] (a long-running capture) with: [args] itself on Windows (see [adopt]), else wrapped in
     * the `sh` watcher. The caller must keep the process's stdin open (never close [Process.getOutputStream]) for as
     * long as the capture should run.
     */
    fun command(args: List<String>, windows: Boolean = OsInfo.isWindows): List<String> =
        if (windows) args else listOf("/bin/sh", "-c", WATCH_STDIN_SCRIPT, "sh") + args

    /** Puts [proc] (just started) in the kill-on-close job on Windows; elsewhere nothing to do. Never throws. */
    fun adopt(proc: Process) {
        if (!OsInfo.isWindows) return
        runCatching {
            val job = job ?: return
            if (!assign(job, proc.pid())) {
                logger.warn("Screen capture {} could not join the game's job (error {}): it would outlive a crash.",
                    proc.pid(), Native.getLastError())
            }
        }.onFailure { logger.warn("Screen capture job unavailable: {}", it.toString()) }
    }

    /** The game's kill-on-close job, created on first use; null when Windows refused it. */
    private val job: Pointer? by lazy {
        createKillOnCloseJob().also { if (it == null) logger.warn("Could not create the screen capture job (error {}).", Native.getLastError()) }
    }

    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001

    /**
     * A new job whose processes are killed when its last handle closes; null on failure. The structure is
     * JOBOBJECT_EXTENDED_LIMIT_INFORMATION (144 bytes on 64-bit, 112 on 32-bit); LimitFlags sits after the two
     * LARGE_INTEGER time limits on both.
     */
    internal fun createKillOnCloseJob(): Pointer? {
        val k = WinApi.kernel32
        val job = k.CreateJobObjectW(null, null) ?: return null
        val size = if (Native.POINTER_SIZE == 8) 144 else 112
        val info = Memory(size.toLong()).apply {
            clear()
            setInt(16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
        }
        if (!k.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, size)) {
            k.CloseHandle(job)
            return null
        }
        return job
    }

    /** Assigns process [pid] to [job]. */
    internal fun assign(job: Pointer, pid: Long): Boolean {
        val k = WinApi.kernel32
        val handle = k.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, pid.toInt()) ?: return false
        return try {
            k.AssignProcessToJobObject(job, handle)
        } finally {
            k.CloseHandle(handle)
        }
    }
}
