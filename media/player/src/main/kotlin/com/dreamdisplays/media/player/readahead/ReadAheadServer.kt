package com.dreamdisplays.media.player.readahead

import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Loopback HTTP endpoint the decoders read direct media files from, backed by one
 * [ReadAheadSession] per display and file. Listens on 127.0.0.1 only, on a random port; every
 * session lives under an unguessable 128-bit path token, and anything else is a 404.
 */
internal class ReadAheadServer(
    private val upstream: Upstream = HttpUpstream,
    private val refresher: (String) -> String?,
    private val config: ReadAheadSession.Config = ReadAheadSession.Config(),
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val globalBytes = AtomicLong()
    private val fetchExecutor: ExecutorService =
        Executors.newCachedThreadPool { r -> daemon(r, "MediaPlayer-read-ahead-fetch") }

    private data class Key(val displayId: UUID, val originalUrl: String)

    private val byKey = LinkedHashMap<Key, ReadAheadSession>()
    private val byToken = HashMap<String, ReadAheadSession>()

    @Volatile
    private var server: ServerSocket? = null

    /** Bytes buffered across every session. */
    val totalBufferedBytes: Long get() = globalBytes.get()

    /**
     * Returns the loopback URL serving [file] (currently reachable at [upstreamUrl]) for [displayId],
     * reusing that display's session for the same file so a re-resolve keeps its buffer. Null when the
     * loopback socket cannot be opened.
     */
    fun register(displayId: UUID, file: ReadAhead.DirectFile, upstreamUrl: String, windowBytes: Long): String? {
        val port = ensureStarted() ?: return null
        val session = synchronized(byKey) {
            val key = Key(displayId, file.originalUrl)
            val existing = byKey[key]?.takeIf { it.total == file.contentLength }
            if (existing != null) {
                existing.update(upstreamUrl, windowBytes)
                existing
            } else {
                byKey.remove(key)?.let { stale ->
                    stale.close()
                    byToken.remove(stale.token)
                }
                val token = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(random::nextBytes))
                ReadAheadSession(
                    token, file.originalUrl, upstreamUrl, file.contentLength, windowBytes,
                    upstream, refresher, globalBytes, fetchExecutor, config,
                    initialContentType = file.contentType,
                ).also {
                    byKey[key] = it
                    byToken[token] = it
                    evictExcess(keep = it)
                }
            }
        }
        return "http://$LOOPBACK_HOST:$port/${session.token}"
    }

    /**
     * Closes sessions past [maxSessions]: only ones no decoder is reading, longest idle first (a stopped display's,
     * whose idle clock [release] winds back, before a paused one's). A session being read is never cut off, however
     * old: that freezes and reloads a display that is playing. Caller holds the [byKey] lock.
     */
    private fun evictExcess(keep: ReadAheadSession) {
        val excess = byKey.size - maxSessions
        if (excess <= 0) return
        val idle = byKey.entries.mapNotNull { (key, session) ->
            if (session === keep) null else session.idleSinceNanos()?.let { key to it }
        }
        for (key in evictionOrder(idle, excess)) {
            val session = byKey.remove(key) ?: continue
            session.close()
            byToken.remove(session.token)
        }
    }

    /** True when [url] names a live session of this server. */
    fun owns(url: String): Boolean {
        val port = server?.localPort ?: return false
        val prefix = "http://$LOOPBACK_HOST:$port/"
        if (!url.startsWith(prefix)) return false
        val token = url.substring(prefix.length).substringBefore('?')
        return synchronized(byKey) { byToken.containsKey(token) }
    }

    /** Lets [displayId]'s sessions drop their buffers; they re-fill if read again. */
    /** Bytes cached for [displayId] across its sessions, or -1 when it has none. */
    fun bufferedBytes(displayId: UUID): Long {
        val sessions = synchronized(byKey) { byKey.filterKeys { it.displayId == displayId }.values.toList() }
        return if (sessions.isEmpty()) -1L else sessions.sumOf { it.bufferedBytes }
    }

    fun release(displayId: UUID) {
        val sessions = synchronized(byKey) { byKey.filterKeys { it.displayId == displayId }.values.toList() }
        sessions.forEach(ReadAheadSession::release)
    }

    internal fun session(token: String): ReadAheadSession? = synchronized(byKey) { byToken[token] }

    /** Ends every session for good (nothing plays any more): buffers dropped, streamers and fetches stop. */
    fun closeSessions() {
        val all = synchronized(byKey) {
            byKey.values.toList().also {
                byKey.clear()
                byToken.clear()
            }
        }
        all.forEach(ReadAheadSession::close)
    }

    override fun close() {
        server?.close()
        server = null
        synchronized(byKey) {
            byKey.values.forEach(ReadAheadSession::close)
            byKey.clear()
            byToken.clear()
        }
        fetchExecutor.shutdownNow()
    }

    @Synchronized
    private fun ensureStarted(): Int? {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
            server = socket
            daemon({ acceptLoop(socket) }, "MediaPlayer-read-ahead-http").start()
            socket.localPort
        } catch (e: IOException) {
            logger.warn("Read-ahead buffer unavailable, direct files will stream unbuffered: ${e.message}.")
            null
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            daemon({ serve(client) }, "MediaPlayer-read-ahead-conn").start()
        }
    }

    private class Request(val method: String, val target: String, val range: String?, val close: Boolean)

    private fun serve(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = IDLE_CONNECTION_MS
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream(), OUTPUT_BUFFER)
                // Keep-alive: decoders that reuse a connection across seeks get one; any reply that had
                // to be cut short ends the connection so the client sees an error rather than a clean end.
                while (true) {
                    val request = readRequest(input) ?: return
                    if (!respond(request, out) || request.close) return
                }
            }
        } catch (e: IOException) {
            logger.debug("Read-ahead connection ended: {}.", e.message)
        }
    }

    /** Answers one request; false when the connection must be closed. */
    private fun respond(request: Request, out: OutputStream): Boolean {
        val token = request.target.removePrefix("/").substringBefore('?')
        val session = session(token) ?: return writeStatus(out, 404, "Not Found")
        if (request.method != "GET" && request.method != "HEAD") return writeStatus(out, 405, "Method Not Allowed")
        val total = session.total
        val range = ReadAheadMath.parseRange(request.range, total)
            ?: return writeStatus(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$total\r\n")
        val partial = request.range != null
        val head = StringBuilder()
            .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ").append(session.contentType ?: DEFAULT_CONTENT_TYPE).append("\r\n")
            .append("Content-Length: ").append(range.last - range.first + 1).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
        if (partial) {
            head.append("Content-Range: bytes ").append(range.first).append('-').append(range.last)
                .append('/').append(total).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (request.method == "HEAD") {
            out.flush()
            return true
        }
        return session.serve(range.first, range.last, out)
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input)?.takeIf { it.isNotBlank() } ?: return null
        var range: String? = null
        var close = requestLine.endsWith("HTTP/1.0")
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when {
                name.equals("Range", ignoreCase = true) -> range = value
                name.equals("Connection", ignoreCase = true) -> close = value.equals("close", ignoreCase = true)
            }
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        return Request(parts[0].uppercase(), parts[1], range, close)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
            if (line.length > MAX_LINE_BYTES) return null
        }
        return line.toString()
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String, extra: String = ""): Boolean {
        out.write("HTTP/1.1 $code $reason\r\n${extra}Content-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        return true
    }

    internal companion object {
        /** The [excess] keys to evict out of [idle] (key, idle since), longest idle first. */
        internal fun <K> evictionOrder(idle: List<Pair<K, Long>>, excess: Int): List<K> =
            idle.sortedWith { a, b -> a.second.compareTo(b.second) }.take(excess.coerceAtLeast(0)).map { it.first }

        const val LOOPBACK_HOST = "127.0.0.1"
        const val TOKEN_BYTES = 16
        const val BACKLOG = 64
        const val DEFAULT_MAX_SESSIONS = 24
        const val IDLE_CONNECTION_MS = 120_000
        const val MAX_LINE_BYTES = 8 * 1024
        const val OUTPUT_BUFFER = 64 * 1024
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
