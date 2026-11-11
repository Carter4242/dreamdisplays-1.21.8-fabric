package com.dreamdisplays.media.runtime.download

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SourceLoaderChecksumTest {
    private val payload = "yt-dlp zipapp bytes".toByteArray()
    private val good = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private var sums = "$good  yt-dlp\n${"0".repeat(64)}  yt-dlp.exe\n"

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/r/yt-dlp") { ex -> ex.sendResponseHeaders(200, payload.size.toLong()); ex.responseBody.use { it.write(payload) } }
        createContext("/r/SHA2-256SUMS") { ex ->
            val body = sums.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}/r/"
    private val dir = Files.createTempDirectory("verified")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `sha256sum lists parse in text and binary mode`() {
        val sums = SourceLoader.parseSha256Sums("${"A".repeat(64)}  yt-dlp\n${"b".repeat(64)} *yt-dlp.exe\r\nnoise\n")
        assertEquals(mapOf("yt-dlp" to "a".repeat(64), "yt-dlp.exe" to "b".repeat(64)), sums)
    }

    @Test
    fun `a download matching its published checksum lands`() {
        val dest = dir.resolve("yt-dlp.pyz")
        SourceLoader.downloadVerified(base + "yt-dlp", base + "SHA2-256SUMS", dest, "yt-dlp")
        assertContentEquals(payload, dest.readBytes())
    }

    @Test
    fun `a mismatching or unlisted download never replaces the installed copy`() {
        val dest = dir.resolve("yt-dlp.pyz")
        Files.write(dest, "old".toByteArray())
        sums = "${"0".repeat(64)}  yt-dlp\n"
        assertFailsWith<SourceLoader.ChecksumMismatchException> {
            SourceLoader.downloadVerified(base + "yt-dlp", base + "SHA2-256SUMS", dest, "yt-dlp")
        }
        sums = "$good  something-else\n"
        assertFailsWith<IOException> { SourceLoader.downloadVerified(base + "yt-dlp", base + "SHA2-256SUMS", dest, "yt-dlp") }
        assertContentEquals("old".toByteArray(), dest.readBytes())
        assertFalse(dir.resolve("yt-dlp.pyz.part").exists())
    }
}
