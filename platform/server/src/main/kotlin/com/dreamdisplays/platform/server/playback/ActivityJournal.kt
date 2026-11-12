package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.activity.ActivityJournalFormat
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The permanent Activity journal: every Activity row and every refused attempt, one JSON line each
 * ([ActivityJournalFormat]), appended to `activity/activity-YYYY-MM-DD.log` in the plugin folder by one background
 * thread. The queue is bounded: when the disk cannot keep up, lines are dropped and the next one written says how many.
 * Files older than `[activity] log_days` are deleted when the day changes (0 keeps them all). Independent of the
 * in-memory list and of who may see it.
 */
object ActivityJournal {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val QUEUE_LINES = 10_000

    /** The journal folder (the platform points it at `plugins/DreamDisplays/activity`); null writes nothing. */
    var dir: () -> File? = { null }

    /** `[activity] log_days` (0 = keep forever). */
    var keepDays: () -> Int = { 90 }

    var zone: ZoneId = ZoneId.systemDefault()

    private val dropped = AtomicLong()

    @Volatile
    private var writer: ThreadPoolExecutor? = null

    // Writer thread only
    private var openDay: LocalDate? = null
    private var openFile: File? = null
    private var out: BufferedWriter? = null

    /** Queues [line] for the journal; never blocks the caller. */
    fun write(line: String) {
        val target = dir() ?: return
        val executor = writer ?: synchronized(this) { writer ?: newWriter().also { writer = it } }
        try {
            executor.execute { append(target, line) }
        } catch (_: RejectedExecutionException) {
            dropped.incrementAndGet()
        }
    }

    /** Writes what is queued and stops the writer thread (plugin disable). */
    fun shutdown() {
        val executor = synchronized(this) { writer.also { writer = null } } ?: return
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        runCatching { out?.close() }
        out = null
        openDay = null
        openFile = null
    }

    private fun newWriter(): ThreadPoolExecutor =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_LINES)) { r ->
            Thread(r, "DreamDisplays-activity-log").apply { isDaemon = true }
        }.apply { rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy() }

    private fun append(target: File, line: String) {
        try {
            val today = LocalDate.now(zone)
            val file = File(target, ActivityJournalFormat.fileName(today))
            if (today != openDay || file != openFile || out == null) {
                runCatching { out?.close() }
                if (!target.isDirectory && !target.mkdirs()) {
                    logger.warn("Could not create {}.", target)
                    return
                }
                out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8))
                val newDay = openDay != today
                openDay = today
                openFile = file
                if (newDay) prune(target, today)
            }
            val w = out ?: return
            val lost = dropped.getAndSet(0)
            if (lost > 0) w.write("{\"event\":\"dropped\",\"lines\":$lost}\n")
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            logger.warn("Could not write the activity log: {}", e.toString())
            runCatching { out?.close() }
            out = null
        }
    }

    private fun prune(target: File, today: LocalDate) {
        val keep = keepDays()
        target.listFiles()?.forEach { f ->
            if (ActivityJournalFormat.expired(f.name, today, keep) && !f.delete()) logger.warn("Could not delete {}.", f)
        }
    }

    /** The journal files, newest day first. */
    fun files(): List<File> = dir()?.listFiles()
        ?.filter { ActivityJournalFormat.dateOf(it.name) != null }
        ?.sortedByDescending { it.name }
        .orEmpty()
}
