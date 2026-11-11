package com.dreamdisplays.media.player.pipeline

import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A live session's picture timestamps, matched to when each frame was shown in game. The decoding `FFmpeg` writes one
 * line per output frame (`n pts tb`, see [ffmpegArgs]) to a small file this tails; the frame pipe numbers the frames it
 * reads the same way and reports each one the render thread uploads ([onPresented]). The result, per display ([key]),
 * is the recent (stream PTS, presentation `System.nanoTime`) pairs ([recent]): with a screen share's timestamps (the
 * capture time on the server's clock) and the clock offset, that is the glass-to-glass latency.
 */
class LiveFrameStamps(private val key: String) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Where `FFmpeg` writes the stamps; deleted on [close]. */
    val path: String = File(System.getProperty("java.io.tmpdir"), "$FILE_PREFIX${UUID.randomUUID()}$FILE_SUFFIX")
        .also { sweepStaleOnce(it.parentFile); it.deleteOnExit() }.absolutePath

    private val frameAt = LongArray(RING) { -1L }
    private val ptsAt = LongArray(RING)

    /** Frames shown before their stamp was read (the stats file is written a little behind the picture). */
    private val waitingFrame = LongArray(RING) { -1L }
    private val waitingAt = LongArray(RING)
    private val shownPts = LongArray(RING)
    private val shownAt = LongArray(RING)
    private var shownCount = 0L

    @Volatile
    private var closed = false

    private val tailer = Thread(::tail, "DreamDisplays-live-stamps").apply { isDaemon = true }

    /** Starts tailing the stamps file and registers this as [key]'s current stamps. */
    fun start() {
        active[key] = this
        tailer.start()
    }

    /** Frame [frame] (0-based, in output order) was uploaded for display at [nanos]. Any thread. */
    fun onPresented(frame: Long, nanos: Long) {
        synchronized(this) {
            val slot = (frame % RING).toInt()
            if (frameAt[slot] == frame) {
                record(ptsAt[slot], nanos)
            } else {
                // Its stamp is not read yet: matched when it is
                waitingFrame[slot] = frame
                waitingAt[slot] = nanos
            }
        }
    }

    private fun record(pts: Long, nanos: Long) {
        val i = (shownCount % RING).toInt()
        shownPts[i] = pts
        shownAt[i] = nanos
        shownCount++
    }

    /** Up to [max] latest (PTS µs, presentation nanos) pairs, in the order they were matched. */
    fun recent(max: Int = RING): List<Pair<Long, Long>> = synchronized(this) {
        val n = minOf(shownCount, max.toLong(), RING.toLong()).toInt()
        (0 until n).map { k ->
            val i = ((shownCount - n + k) % RING).toInt()
            shownPts[i] to shownAt[i]
        }
    }

    fun close() {
        closed = true
        active.remove(key, this)
        tailer.interrupt()
    }

    private fun tail() {
        val file = File(path)
        var raf: RandomAccessFile? = null
        val pending = StringBuilder()
        val buf = ByteArray(8192)
        try {
            while (!closed) {
                if (raf == null) {
                    if (file.exists()) raf = RandomAccessFile(file, "r") else {
                        Thread.sleep(POLL_MS); continue
                    }
                }
                val n = raf.read(buf)
                if (n <= 0) {
                    Thread.sleep(POLL_MS); continue
                }
                pending.append(String(buf, 0, n, Charsets.US_ASCII))
                while (true) {
                    val nl = pending.indexOf("\n")
                    if (nl < 0) break
                    parse(pending.substring(0, nl))
                    pending.delete(0, nl + 1)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed) logger.debug("Live stamps stopped: {}", e.toString())
        } finally {
            runCatching { raf?.close() }
            runCatching { file.delete() }
        }
    }

    private fun parse(line: String) {
        val stamp = parseLine(line) ?: return
        synchronized(this) {
            val slot = (stamp.first % RING).toInt()
            frameAt[slot] = stamp.first
            ptsAt[slot] = stamp.second
            if (waitingFrame[slot] == stamp.first) {
                record(stamp.second, waitingAt[slot])
                waitingFrame[slot] = -1L
            }
        }
    }

    companion object {
        private const val RING = 512
        private const val POLL_MS = 15L

        private val active = ConcurrentHashMap<String, LiveFrameStamps>()

        private const val FILE_PREFIX = "dreamdisplays-live-"
        private const val FILE_SUFFIX = ".txt"

        /** A stamps file untouched this long belongs to no running session (a crashed game left it behind). */
        private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

        private val swept = java.util.concurrent.atomic.AtomicBoolean()

        private fun sweepStaleOnce(dir: File?) {
            if (dir == null || !swept.compareAndSet(false, true)) return
            runCatching { sweepStale(dir, System.currentTimeMillis()) }
        }

        /**
         * Deletes stamps files in [dir] last written more than [STALE_AFTER_MS] before [nowMillis]: a running session
         * writes its file every frame, so only files from a game that exited without cleaning up are that old.
         * Returns how many were deleted.
         */
        internal fun sweepStale(dir: File, nowMillis: Long): Int =
            dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
                .orEmpty()
                .count { f -> nowMillis - f.lastModified() > STALE_AFTER_MS && f.delete() }

        /** The stamps of [key]'s running live session, or null. */
        fun of(key: String): LiveFrameStamps? = active[key]

        /** `FFmpeg` output options writing [path]: timestamps kept as the stream has them, one line per frame. */
        fun ffmpegArgs(path: String): List<String> = listOf(
            "-copyts", "-enc_time_base:v", "1:1000",
            "-stats_mux_pre", path, "-stats_mux_pre_fmt", "{n} {pts} {tb}",
        )

        /** `n pts num/den` -> (n, PTS in µs), or null. */
        internal fun parseLine(line: String): Pair<Long, Long>? {
            val parts = line.trim().split(' ')
            if (parts.size < 3) return null
            val n = parts[0].toLongOrNull() ?: return null
            val pts = parts[1].toLongOrNull() ?: return null
            val tb = parts[2].split('/')
            val num = tb.getOrNull(0)?.toLongOrNull() ?: return null
            val den = tb.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return n to pts * num * 1_000_000L / den
        }
    }
}
