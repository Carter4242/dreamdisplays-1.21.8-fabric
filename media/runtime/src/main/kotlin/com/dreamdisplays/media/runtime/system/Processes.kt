package com.dreamdisplays.media.runtime.system

import com.dreamdisplays.util.OsInfo
import kotlinx.io.IOException
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Shared subprocess plumbing for the external binaries. */
object Processes {
    /**
     * Forcibly destroys [process] and all of its descendants, children first, so orphaned
     * grandchildren don't outlive the kill.
     */
    fun destroyTree(process: Process) {
        runCatching {
            val tree = process.toHandle().descendants().toList()
            val pids = tree.mapTo(HashSet()) { it.pid() }
            fun depth(handle: ProcessHandle): Int {
                var d = 0
                var p = handle.parent().orElse(null)
                while (p != null && p.pid() in pids) {
                    d++
                    p = p.parent().orElse(null)
                }
                return d
            }
            tree.sortedByDescending(::depth).forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
    }

    /**
     * Starts a daemon thread that silently drains and discards [input]. Required for any subprocess
     * whose output is not consumed — a full OS pipe buffer blocks the child forever.
     */
    fun drainAsync(input: InputStream): Thread =
        Thread {
            runCatching { input.use { it.readAllBytes() } }
        }.apply {
            isDaemon = true
            start()
        }

    /** Most characters [collector] keeps by default: far above any real `yt-dlp -J` (a few MB), far below a heap. */
    const val MAX_COLLECTED_CHARS = 16 * 1024 * 1024

    /**
     * Creates (without starting) a daemon thread named [name] that reads [input] as UTF-8 text into
     * [sink]. Call [Thread.start] then [Thread.join] after the process exits. Past [maxChars] the rest is
     * read and dropped (the child never blocks on a full pipe), so output a page controls cannot exhaust the heap.
     */
    fun collector(input: InputStream, sink: StringBuilder, name: String, maxChars: Int = MAX_COLLECTED_CHARS): Thread =
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { r ->
                    val buf = CharArray(8192)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) {
                        val room = maxChars - sink.length
                        if (room > 0) sink.appendRange(buf, 0, minOf(n, room))
                    }
                }
            }.onFailure { e -> if (e !is IOException) throw e }
        }, name).apply { isDaemon = true }

    /** Marks [binary] world-executable, falling back to [java.io.File.setExecutable] on non-POSIX filesystems. */
    fun markExecutable(binary: Path) {
        runCatching {
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"))
        }.onFailure { e ->
            if (e !is UnsupportedOperationException && e !is IOException) throw e
            binary.toFile().setExecutable(true, false)
        }
    }

    /** Removes the macOS quarantine flag from a downloaded [binary] so Gatekeeper doesn't block it. */
    fun removeMacQuarantine(binary: Path) {
        if (!OsInfo.isMac) return
        runCatching {
            ProcessBuilder("xattr", "-d", "com.apple.quarantine", binary.toString())
                .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
        }
    }
}
