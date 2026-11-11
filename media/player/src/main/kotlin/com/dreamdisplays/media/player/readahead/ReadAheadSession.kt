package com.dreamdisplays.media.player.readahead

import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/** One upstream response: [status], the file size from `Content-Range` / `Content-Length`, and the body. */
internal class UpstreamBody(
    val status: Int,
    val total: Long?,
    val contentType: String?,
    val stream: java.io.InputStream?,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        // Tear the connection down first: closing a half-read body on its own may block draining it
        runCatching { onClose() }
        runCatching { stream?.close() }
    }
}

/** Opens byte ranges of a remote file. */
internal fun interface Upstream {
    /** Requests bytes [from]..[to] (inclusive) of [url]. Throws [IOException] when no response arrives. */
    @Throws(IOException::class)
    fun open(url: String, from: Long, to: Long): UpstreamBody
}

/** The upstream answered with an HTTP status the buffer cannot use. */
internal class UpstreamStatusException(val status: Int) : IOException("Upstream answered HTTP $status.")

/**
 * Read-ahead buffer for one remote file played by one display.
 *
 * The file is cached in fixed-size blocks. Every reader (the video decoder, the audio decoder, a
 * scrub preview) is served from those blocks; a block a reader needs that nobody is fetching is
 * fetched on demand, and a streamer thread keeps the next [windowBytes] past playback downloaded.
 * Playback position is the slowest long-lived reader, so the two decoders reading the same
 * interleaved file share one download, and a brief extra reader (a scrub preview, a demuxer reading
 * the index at the end of the file) never drags the window away.
 *
 * Memory is bounded per session by [Config.sessionCapBytes] and across sessions by
 * [Config.globalCapBytes] (via [globalBytes]); everything is dropped once no reader has been
 * connected for [Config.idleMs]. A signed URL that expires mid-film (401/403/404/410) is replaced by
 * re-resolving [originalUrl] through [refresher].
 */
internal class ReadAheadSession(
    val token: String,
    val originalUrl: String,
    upstreamUrl: String,
    val total: Long,
    windowBytes: Long,
    private val upstream: Upstream,
    private val refresher: (String) -> String?,
    private val globalBytes: AtomicLong,
    private val fetchExecutor: ExecutorService,
    private val config: Config = Config(),
    private val random: Random = Random.Default,
    initialContentType: String? = null,
) {
    /** Tunables; the defaults are what the client runs with. */
    data class Config(
        val blockSize: Int = 256 * 1024,
        val sessionCapBytes: Long = 64L shl 20,
        val globalCapBytes: Long = 192L shl 20,
        val keepBehindBytes: Long = 4L shl 20,
        val minLeadBytes: Long = 4L shl 20,
        val requestMaxBytes: Long = 8L shl 20,
        val onDemandBytes: Long = 2L shl 20,
        val stallTimeoutMs: Long = 45_000L,
        val establishedMs: Long = 1_500L,
        val idleMs: Long = 15_000L,
        val refreshIntervalMs: Long = 10_000L,
        val maxBackoffMs: Long = 15_000L,
    )

    private val logger = LoggerFactory.getLogger(javaClass)

    private class Block(val index: Long, size: Int) {
        val data = ByteArray(size)
        var filled = 0
        var complete = false
    }

    private class Reader(@JvmField var pos: Long, @JvmField val openedNanos: Long)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val blocks = HashMap<Long, Block>()
    private val readers = LinkedHashSet<Reader>()
    private val expiredUrls = HashSet<String>()

    private var cachedBytes = 0L
    private var streamerRunning = false
    private var closed = false
    private var lastReaderGoneNanos = System.nanoTime()
    private var retryAtNanos = 0L
    private var failures = 0
    private var lastRefreshNanos = 0L

    @Volatile
    var upstreamUrl: String = upstreamUrl
        private set

    @Volatile
    var windowBytes: Long = windowBytes
        private set

    /** `Content-Type` the host reported, once a response has arrived. */
    @Volatile
    var contentType: String? = initialContentType
        private set

    /** Bytes currently cached by this session. */
    val bufferedBytes: Long get() = lock.withLock { cachedBytes }

    /** True when the session currently holds a block starting at [pos]'s block, filled or in flight. */
    internal fun hasBlockAt(pos: Long): Boolean = lock.withLock { blocks.containsKey(pos / config.blockSize) }

    /**
     * Points the session at a freshly resolved [url] and window. A URL this session already saw
     * expire is ignored, so a stale resolver cache cannot undo a refresh.
     */
    fun update(url: String, window: Long) = lock.withLock {
        if (url != upstreamUrl && url !in expiredUrls) upstreamUrl = url
        windowBytes = window
        changed.signalAll()
    }

    /**
     * Writes bytes [from]..[to] to [out], waiting for the network as needed. Returns false when the
     * response had to be cut short (upstream stalled past [Config.stallTimeoutMs], or the session
     * closed); the caller must then drop the connection so the client sees an error, not a clean end.
     */
    fun serve(from: Long, to: Long, out: OutputStream): Boolean {
        val reader = Reader(from, System.nanoTime())
        lock.withLock {
            readers += reader
            ensureStreamer()
            changed.signalAll()
        }
        try {
            var pos = from
            while (pos <= to) {
                val block = awaitData(pos) ?: return false
                val offset = (pos - block.index * config.blockSize).toInt()
                val available = lock.withLock { block.filled } - offset
                val length = minOf(available.toLong(), to - pos + 1).toInt()
                out.write(block.data, offset, length)
                pos += length
                lock.withLock {
                    reader.pos = pos
                    changed.signalAll()
                }
            }
            out.flush()
            return true
        } finally {
            lock.withLock {
                readers -= reader
                if (readers.isEmpty()) lastReaderGoneNanos = System.nanoTime()
                changed.signalAll()
            }
        }
    }

    /** When the last reader left ([System.nanoTime]), or null while a decoder is reading this session. */
    fun idleSinceNanos(): Long? = lock.withLock { if (readers.isEmpty()) lastReaderGoneNanos else null }

    /** Drops the cached data unless a reader is still connected; the token stays valid and re-fills on demand. */
    fun release() = lock.withLock {
        if (readers.isNotEmpty()) return@withLock
        lastReaderGoneNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(config.idleMs)
        dropIdleBlocks()
        changed.signalAll()
    }

    /** Drops everything for good; connected readers are cut off. */
    fun close() = lock.withLock {
        closed = true
        dropIdleBlocks()
        changed.signalAll()
    }

    private fun awaitData(pos: Long): Block? {
        val index = pos / config.blockSize
        val offset = (pos - index * config.blockSize).toInt()
        val stallNanos = TimeUnit.MILLISECONDS.toNanos(config.stallTimeoutMs)
        lock.withLock {
            var deadline = System.nanoTime() + stallNanos
            var lastFilled = 0
            while (true) {
                if (closed) return null
                val block = blocks[index]
                if (block != null && block.filled > offset) return block
                // Bytes arriving for the block being waited for restart the stall clock; a fetch that
                // is merely claimed, failed or retried does not, so a dead host cannot hold a reader forever
                val filledNow = block?.filled ?: 0
                if (filledNow > lastFilled) deadline = System.nanoTime() + stallNanos
                lastFilled = filledNow
                val now = System.nanoTime()
                if (block == null && now >= retryAtNanos) {
                    claim(index, strict = false)?.let { first ->
                        val end = minOf(index * config.blockSize + config.onDemandBytes, total) - 1
                        fetchExecutor.execute { runFetch(first, end, streamer = false) }
                    }
                }
                val remaining = deadline - now
                if (remaining <= 0L) {
                    logger.warn("Read-ahead: no data for ${config.stallTimeoutMs} ms at byte $pos; dropping the reader.")
                    return null
                }
                changed.awaitNanos(minOf(remaining, WAIT_SLICE_NANOS))
            }
        }
    }

    private fun ensureStreamer() {
        if (streamerRunning || closed) return
        streamerRunning = true
        daemon({ streamLoop() }, "MediaPlayer-read-ahead").start()
    }

    private fun streamLoop() {
        try {
            while (true) {
                val (first, end) = lock.withLock { nextStreamerFetch() } ?: return
                runFetch(first, end, streamer = true)
            }
        } finally {
            lock.withLock { streamerRunning = false }
        }
    }

    /** Waits until the streamer has something to fetch; null when it should exit. Holds [lock]. */
    private fun nextStreamerFetch(): Pair<Block, Long>? {
        while (true) {
            if (closed) return null
            val now = System.nanoTime()
            if (readers.isEmpty()) {
                if (now - lastReaderGoneNanos >= TimeUnit.MILLISECONDS.toNanos(config.idleMs)) {
                    dropIdleBlocks()
                    return null
                }
                changed.awaitNanos(WAIT_SLICE_NANOS)
                continue
            }
            if (now < retryAtNanos) {
                changed.awaitNanos(minOf(retryAtNanos - now, WAIT_SLICE_NANOS))
                continue
            }
            val anchor = anchorPos(now) ?: continue
            val firstIndex = anchor / config.blockSize
            val endIndex = (minOf(anchor + windowBytes, total) - 1) / config.blockSize
            var index = firstIndex
            while (index <= endIndex && blocks.containsKey(index)) index++
            if (index > endIndex) {
                changed.awaitNanos(WAIT_SLICE_NANOS)
                continue
            }
            val claimed = claim(index, strict = true)
            if (claimed == null) {
                changed.awaitNanos(WAIT_SLICE_NANOS)
                continue
            }
            var runEnd = index
            val maxBlocks = (config.requestMaxBytes / config.blockSize).coerceAtLeast(1L)
            while (runEnd + 1 <= endIndex && runEnd + 1 - index < maxBlocks && !blocks.containsKey(runEnd + 1)) runEnd++
            return claimed to (minOf((runEnd + 1) * config.blockSize, total) - 1)
        }
    }

    /**
     * Downloads [first] onward up to byte [end] into consecutive blocks, claiming each next block as
     * it goes and stopping early when another fetch already has it, when (for the [streamer]) it
     * falls outside the window, or when the buffer is full.
     */
    private fun runFetch(first: Block, end: Long, streamer: Boolean) {
        var block = first
        val url = upstreamUrl
        try {
            val from = first.index * config.blockSize
            upstream.open(url, from, end).use { body ->
                checkResponse(body, from)
                body.contentType?.let { contentType = it }
                val input = body.stream ?: throw EOFException("Upstream sent no body.")
                while (true) {
                    val room = block.data.size - block.filled
                    val read = input.read(block.data, block.filled, minOf(room, READ_SLICE))
                    if (read < 0) throw EOFException("Upstream ended at ${block.index * config.blockSize + block.filled}.")
                    val next = lock.withLock {
                        block.filled += read
                        changed.signalAll()
                        if (block.filled < block.data.size) return@withLock block
                        block.complete = true
                        val nextIndex = block.index + 1
                        if (nextIndex * config.blockSize > end || closed) return@withLock null
                        if (streamer && !insideWindow(nextIndex)) return@withLock null
                        claim(nextIndex, strict = streamer)
                    } ?: break
                    block = next
                }
            }
            lock.withLock { failures = 0 }
        } catch (e: Exception) {
            onFetchFailure(url, e)
        } finally {
            lock.withLock {
                if (!block.complete && blocks[block.index] === block) removeBlock(block)
                changed.signalAll()
            }
        }
    }

    private fun checkResponse(body: UpstreamBody, from: Long) {
        val acceptable = body.status == 206 || (body.status == 200 && from == 0L)
        if (!acceptable) throw UpstreamStatusException(body.status)
        val reported = body.total
        if (reported != null && reported != total) {
            throw UpstreamStatusException(STATUS_CHANGED_FILE)
        }
    }

    private fun onFetchFailure(url: String, e: Exception) {
        val status = (e as? UpstreamStatusException)?.status
        val expired = status == 401 || status == 403 || status == 404 || status == 410
        val now = System.nanoTime()
        val refresh = lock.withLock {
            // Another fetch already replaced the link this one used: just go again with the new one
            if (expired && url != upstreamUrl) {
                changed.signalAll()
                return
            }
            failures++
            val due = expired && now - lastRefreshNanos >= TimeUnit.MILLISECONDS.toNanos(config.refreshIntervalMs)
            if (due) lastRefreshNanos = now
            retryAtNanos = now + TimeUnit.MILLISECONDS.toNanos(backoffMs(failures))
            changed.signalAll()
            due
        }
        logger.debug("Read-ahead fetch failed ({} in a row): {}.", failures, e.message)
        if (!refresh) return
        // A signed link (a CDN redirect target) expired: walk the original link again for a new one.
        val fresh = runCatching { refresher(originalUrl) }
            .onFailure { logger.debug("Read-ahead could not refresh {}: {}.", originalUrl.take(120), it.message) }
            .getOrNull()
        lock.withLock {
            if (fresh != null && fresh != url) {
                expiredUrls += url
                upstreamUrl = fresh
                retryAtNanos = 0L
                logger.info("Read-ahead: upstream link refreshed after HTTP $status.")
            }
            changed.signalAll()
        }
    }

    /** Back-off after [failures] failed fetches in a row: 0.5 s doubling to [Config.maxBackoffMs], ±25 %. */
    internal fun backoffMs(failures: Int): Long {
        val base = minOf(500L shl (failures - 1).coerceIn(0, 10), config.maxBackoffMs)
        return (base * (0.75 + random.nextDouble() * 0.5)).toLong()
    }

    /** Playback position: the slowest reader that has been open a while, or the slowest of all. Holds [lock]. */
    private fun anchorPos(now: Long): Long? {
        val settledAfter = TimeUnit.MILLISECONDS.toNanos(config.establishedMs)
        return readers.filter { now - it.openedNanos >= settledAfter }.minOfOrNull { it.pos }
            ?: readers.minOfOrNull { it.pos }
    }

    private fun insideWindow(index: Long): Boolean {
        val anchor = anchorPos(System.nanoTime()) ?: return false
        val first = anchor / config.blockSize
        val last = (minOf(anchor + windowBytes, total) - 1) / config.blockSize
        return index in first..last
    }

    /**
     * Reserves block [index] for a fetch, making room first. A [strict] (prefetch) claim only takes
     * room that is free or held by blocks playback no longer needs, and stays under the global cap; a
     * reader that needs the block now always gets it. Null when it is already claimed or there is no
     * room. Holds [lock].
     */
    private fun claim(index: Long, strict: Boolean): Block? {
        if (closed || blocks.containsKey(index)) return null
        val length = ReadAheadMath.blockLength(index, config.blockSize, total)
        if (length <= 0) return null
        if (!makeRoom(length, strict)) return null
        if (strict && globalBytes.get() + length > config.globalCapBytes && leadBytes() >= config.minLeadBytes) {
            return null
        }
        val block = Block(index, length)
        blocks[index] = block
        cachedBytes += length
        globalBytes.addAndGet(length.toLong())
        return block
    }

    private fun makeRoom(length: Int, strict: Boolean): Boolean {
        if (cachedBytes + length <= config.sessionCapBytes) return true
        val anchor = anchorPos(System.nanoTime()) ?: 0L
        val anchorBlock = anchor / config.blockSize
        val windowEnd = (minOf(anchor + windowBytes, total) - 1) / config.blockSize
        val keepBehind = config.keepBehindBytes / config.blockSize
        val candidates = blocks.values.filter { it.complete }.map { it.index }
        for (index in ReadAheadMath.evictionOrder(candidates, anchorBlock, keepBehind, windowEnd)) {
            if (cachedBytes + length <= config.sessionCapBytes) break
            blocks[index]?.let(::removeBlock)
        }
        return cachedBytes + length <= config.sessionCapBytes || !strict
    }

    /** Contiguous bytes cached from the anchor onward. Holds [lock]. */
    private fun leadBytes(): Long {
        val anchor = anchorPos(System.nanoTime()) ?: return 0L
        var index = anchor / config.blockSize
        var lead = 0L
        while (true) {
            val block = blocks[index] ?: break
            lead += block.filled
            if (!block.complete) break
            index++
        }
        return lead
    }

    private fun removeBlock(block: Block) {
        if (blocks.remove(block.index) !== block) return
        cachedBytes -= block.data.size
        globalBytes.addAndGet(-block.data.size.toLong())
    }

    /** Drops every block no fetch is still writing. Holds [lock]. */
    private fun dropIdleBlocks() {
        blocks.values.filter { it.complete }.forEach(::removeBlock)
    }

    private companion object {
        const val READ_SLICE = 64 * 1024
        val WAIT_SLICE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)

        /** Pseudo status for a host now serving a file of a different size under the same link. */
        const val STATUS_CHANGED_FILE = 412
    }
}
