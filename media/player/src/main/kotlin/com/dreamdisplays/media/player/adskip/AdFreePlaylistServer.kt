package com.dreamdisplays.media.player.adskip

import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Loopback HTTP endpoint serving each display's ad-free playlist ([LiveAdFilter]) to its decoders. Listens on 127.0.0.1
 * only, on a random port; each playlist lives under an unguessable 128-bit path token, anything else is a 404. Only the
 * playlist goes through here: its segment URLs point at Twitch's CDN as before.
 */
internal class AdFreePlaylistServer : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val playlists = ConcurrentHashMap<String, () -> String?>()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> daemon(r, "MediaPlayer-ad-skip-http") }

    @Volatile
    private var server: ServerSocket? = null

    /** Serves [playlist] (null: 404) under a new token; returns its URL, or null when the socket cannot be opened. */
    fun register(playlist: () -> String?): Pair<String, String>? {
        val port = ensureStarted() ?: return null
        val token = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        playlists[token] = playlist
        return token to "http://$LOOPBACK_HOST:$port/$token.m3u8"
    }

    fun unregister(token: String) {
        playlists.remove(token)
    }

    /** True when [url] names a playlist this server serves. */
    fun owns(url: String): Boolean {
        val port = server?.localPort ?: return false
        val prefix = "http://$LOOPBACK_HOST:$port/"
        if (!url.startsWith(prefix)) return false
        return playlists.containsKey(url.substring(prefix.length).substringBefore('?').removeSuffix(".m3u8"))
    }

    @Synchronized
    private fun ensureStarted(): Int? {
        server?.let { return it.localPort }
        return runCatching {
            val socket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK_HOST))
            server = socket
            daemon({ acceptLoop(socket) }, "MediaPlayer-ad-skip-accept").start()
            socket.localPort
        }.onFailure { e -> logger.warn("Ad-free playlist endpoint could not open a loopback socket: {}", e.toString()) }
            .getOrNull()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            runCatching {
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()
                val token = path.removePrefix("/").substringBefore('?').removeSuffix(".m3u8")
                val body = if (method == "GET" || method == "HEAD") playlists[token]?.invoke() else null
                val out = s.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                } else {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${bytes.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
                    if (method == "GET") out.write(bytes)
                }
                out.flush()
            }
        }
    }

    override fun close() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
