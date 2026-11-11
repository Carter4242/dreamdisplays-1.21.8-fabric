package com.dreamdisplays.media.player.readahead

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadAheadServerTest {
    private val file = ByteArray(600_000) { (it * 31 + it / 7).toByte() }
    private val display = UUID.randomUUID()

    /** Fake host: serves [file], optionally failing, expiring a URL, or stalling. */
    private inner class FakeHost : Upstream {
        val served = AtomicLong()
        val requests = CopyOnWriteArrayList<String>()
        val failuresLeft = AtomicInteger(0)
        var failStatus = 503
        val expired = CopyOnWriteArrayList<String>()

        @Volatile
        var stall: CountDownLatch? = null

        override fun open(url: String, from: Long, to: Long): UpstreamBody {
            requests += "$url $from-$to"
            if (url in expired) return UpstreamBody(403, null, null, null)
            if (failuresLeft.getAndDecrement() > 0) return UpstreamBody(failStatus, null, null, null)
            val end = minOf(to, file.size - 1L).toInt()
            val bytes = file.copyOfRange(from.toInt(), end + 1)
            val gate = stall
            val body = object : InputStream() {
                private val inner = ByteArrayInputStream(bytes)
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    gate?.await(5, TimeUnit.SECONDS)?.let { if (!it) throw IOException("Read timed out") }
                    val n = inner.read(b, off, minOf(len, 8_192))
                    if (n > 0) served.addAndGet(n.toLong())
                    return n
                }
            }
            return UpstreamBody(206, file.size.toLong(), "video/mp4", body)
        }
    }

    private val host = FakeHost()
    private val refreshes = AtomicInteger()
    private var refreshTo: String? = "https://cdn.example/new"

    private val config = ReadAheadSession.Config(
        blockSize = 4_096,
        sessionCapBytes = 64 * 1_024,
        globalCapBytes = 1 shl 20,
        keepBehindBytes = 8 * 1_024,
        minLeadBytes = 8 * 1_024,
        requestMaxBytes = 32 * 1_024,
        onDemandBytes = 16 * 1_024,
        stallTimeoutMs = 1_500,
        establishedMs = 100,
        idleMs = 300,
        refreshIntervalMs = 2_000,
        maxBackoffMs = 300,
    )

    private val server = ReadAheadServer(
        upstream = host,
        refresher = { refreshes.incrementAndGet(); refreshTo },
        config = config,
    )

    @AfterTest
    fun close() = server.close()

    private fun register(url: String = "https://cdn.example/old", window: Long = 32 * 1_024L): String =
        assertNotNull(
            server.register(
                display,
                ReadAhead.DirectFile("https://host.example/movie.mp4", file.size.toLong(), null, "video/mp4"),
                url, window,
            ),
        )

    private class Response(val status: String, val headers: Map<String, String>, val body: ByteArray)

    /** Sends one request per entry of [ranges] on a single connection (keep-alive) and reads every reply. */
    private fun request(url: String, vararg ranges: String?, method: String = "GET", readDelayMs: Long = 0): List<Response> {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val input = s.getInputStream().buffered()
            return ranges.mapIndexed { i, range ->
                val last = i == ranges.lastIndex
                val req = buildString {
                    append("$method ${uri.rawPath} HTTP/1.1\r\nHost: x\r\n")
                    if (range != null) append("Range: $range\r\n")
                    if (last) append("Connection: close\r\n")
                    append("\r\n")
                }
                out.write(req.toByteArray())
                out.flush()
                readResponse(input, method == "HEAD", readDelayMs)
            }
        }
    }

    private fun readResponse(input: InputStream, headOnly: Boolean, readDelayMs: Long): Response {
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }
        val status = line()
        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = if (headOnly) 0 else headers["content-length"]?.toInt() ?: 0
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (body.size() < length) {
            val n = input.read(buffer, 0, minOf(buffer.size, length - body.size()))
            if (n < 0) break
            body.write(buffer, 0, n)
            if (readDelayMs > 0) Thread.sleep(readDelayMs)
        }
        return Response(status, headers, body.toByteArray())
    }

    @Test
    fun `serves the whole file and ranges from the buffer`() {
        val url = register()
        val whole = request(url, null).single()
        assertEquals("HTTP/1.1 200 OK", whole.status)
        assertEquals(file.size.toString(), whole.headers["content-length"])
        assertEquals("bytes", whole.headers["accept-ranges"])
        assertContentEquals(file, whole.body)

        val (open, bounded) = request(url, "bytes=123457-", "bytes=1000-1999")
        assertEquals("HTTP/1.1 206 Partial Content", open.status)
        assertEquals("bytes 123457-${file.size - 1}/${file.size}", open.headers["content-range"])
        assertContentEquals(file.copyOfRange(123_457, file.size), open.body)
        assertEquals("bytes 1000-1999/${file.size}", bounded.headers["content-range"])
        assertContentEquals(file.copyOfRange(1_000, 2_000), bounded.body, "Second request on the same connection.")
    }

    @Test
    fun `head, unknown tokens and bad ranges`() {
        val url = register()
        val head = request(url, "bytes=0-", method = "HEAD").single()
        assertEquals("HTTP/1.1 206 Partial Content", head.status)
        assertEquals("video/mp4", head.headers["content-type"])
        assertEquals(0, host.requests.size, "A HEAD costs no upstream request.")

        val other = url.substringBeforeLast('/') + "/" + "0".repeat(32)
        assertEquals("HTTP/1.1 404 Not Found", request(other, null).single().status)
        val past = request(url, "bytes=${file.size}-").single()
        assertEquals("HTTP/1.1 416 Range Not Satisfiable", past.status)
        assertEquals("bytes */${file.size}", past.headers["content-range"])
    }

    @Test
    fun `only its own live tokens are trusted`() {
        val url = register()
        assertTrue(server.owns(url))
        assertFalse(server.owns(url.substringBeforeLast('/') + "/" + "f".repeat(32)))
        assertFalse(server.owns("http://127.0.0.1:1/" + url.substringAfterLast('/')))
        assertFalse(server.owns("https://example.com/" + url.substringAfterLast('/')))
    }

    @Test
    fun `the same display and file reuse one session, keeping its token`() {
        val first = register()
        val again = register(url = "https://cdn.example/other")
        assertEquals(first, again)
    }

    @Test
    fun `an expired signed link is refreshed from the original and playback continues`() {
        host.expired += "https://cdn.example/old"
        val url = register()
        val response = request(url, "bytes=0-99999").single()
        assertContentEquals(file.copyOfRange(0, 100_000), response.body)
        assertEquals(1, refreshes.get())
        assertTrue(host.requests.any { it.startsWith("https://cdn.example/new") })

        // A stale resolver cache handing the dead link back must not undo the refresh
        register(url = "https://cdn.example/old")
        assertContentEquals(file.copyOfRange(200_000, 300_000), request(url, "bytes=200000-299999").single().body)
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `server errors are retried with back-off instead of failing the reader`() {
        host.failuresLeft.set(3)
        val url = register()
        val response = request(url, "bytes=0-49999").single()
        assertContentEquals(file.copyOfRange(0, 50_000), response.body)
    }

    @Test
    fun `reads ahead of a slow reader within the memory cap`() {
        val url = register(window = 32 * 1_024L)
        var peak = 0L
        val sampler = thread {
            while (!Thread.currentThread().isInterrupted) {
                val token = url.substringAfterLast('/')
                peak = maxOf(peak, server.session(token)?.bufferedBytes ?: 0L)
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        try {
            // ~4 KiB every 10 ms: the buffer has time to run ahead of it
            val response = request(url, "bytes=0-199999", readDelayMs = 10).single()
            assertContentEquals(file.copyOfRange(0, 200_000), response.body)
        } finally {
            sampler.interrupt()
            sampler.join()
        }
        assertTrue(peak <= config.sessionCapBytes + config.blockSize, "Buffer peaked at $peak bytes.")
        assertTrue(peak >= 24 * 1_024, "The window was actually filled ahead (peak $peak bytes).")
    }

    @Test
    fun `two decoders reading the same bytes share one download`() {
        val url = register()
        val results = arrayOfNulls<ByteArray>(2)
        val readers = (0..1).map { i ->
            thread { results[i] = request(url, "bytes=0-299999", readDelayMs = 2).single().body }
        }
        readers.forEach { it.join() }
        results.forEach { assertContentEquals(file.copyOfRange(0, 300_000), it) }
        assertTrue(host.served.get() < 300_000 * 1.5, "Upstream sent ${host.served.get()} bytes for two readers.")
    }

    @Test
    fun `a stalled host cuts the reply short instead of ending it cleanly`() {
        host.stall = CountDownLatch(1)
        val url = register()
        val response = request(url, "bytes=0-99999").single()
        assertTrue(response.body.size < 100_000, "A reply the buffer could not complete must not look complete.")
        host.stall?.countDown()
    }

    @Test
    fun `an idle session drops its buffer`() {
        val url = register()
        request(url, "bytes=0-49999")
        val session = assertNotNull(server.session(url.substringAfterLast('/')))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.bufferedBytes > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0L, session.bufferedBytes)
        assertEquals(0L, server.totalBufferedBytes)
    }

    @Test
    fun `release drops a stopped display's buffer at once`() {
        val url = register()
        request(url, "bytes=0-49999")
        Thread.sleep(50)
        server.release(display)
        assertEquals(0L, server.session(url.substringAfterLast('/'))!!.bufferedBytes)
    }
}
