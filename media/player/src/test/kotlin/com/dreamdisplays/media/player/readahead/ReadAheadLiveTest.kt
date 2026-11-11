package com.dreamdisplays.media.player.readahead

import com.dreamdisplays.media.runtime.security.MediaHostGuard
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads a real remote file through the buffer. Off by default (it needs the network and a large
 * public file); set `DREAMDISPLAYS_NET_IT` to a direct file URL that redirects to a signed CDN link
 * (a Hugging Face `resolve/main/...mp4`, for instance) to run it. Only a few MiB are read.
 */
class ReadAheadLiveTest {
    private val original: String? = System.getenv("DREAMDISPLAYS_NET_IT")?.takeIf { it.startsWith("https://") }

    private fun direct(url: String, from: Long, to: Long): Pair<ByteArray, Long> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=$from-$to")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            assertEquals(206, c.responseCode)
            val total = c.getHeaderField("Content-Range").substringAfterLast('/').toLong()
            return c.inputStream.use { it.readAllBytes() } to total
        } finally {
            c.disconnect()
        }
    }

    private fun viaProxy(url: String, from: Long, to: Long): ByteArray {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=$from-$to")
        c.readTimeout = 60_000
        try {
            assertEquals(206, c.responseCode)
            assertTrue(c.getHeaderField("Content-Range").startsWith("bytes $from-$to/"))
            return c.inputStream.use { it.readAllBytes() }
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun `reads a remote file through the buffer, including after its signed link expires`() {
        val original = original ?: return
        val signed = MediaHostGuard.resolveSafeUrl(original)
        assertNotEquals(original, signed, "The test file should redirect to a signed link.")
        val (head, total) = direct(signed, 0, 1_048_575)
        val middle = total / 2
        println("Live read-ahead check: $total bytes, signed link on ${URI(signed).host}.")

        ReadAheadServer(refresher = { MediaHostGuard.invalidate(it); MediaHostGuard.resolveSafeUrl(it) }).use { server ->
            val file = ReadAhead.DirectFile(original, total, null, "video/mp4")
            val url = assertNotNull(server.register(UUID.randomUUID(), file, signed, 4L shl 20))
            assertContentEquals(head, viaProxy(url, 0, 1_048_575))
            assertContentEquals(direct(signed, middle, middle + 524_287).first, viaProxy(url, middle, middle + 524_287))

            // A link whose signature no longer verifies is answered 403 by the CDN: the buffer must
            // walk the original link again and carry on.
            val broken = signed.replace(Regex("(Signature=)([A-Za-z0-9])"), "$1x$2")
            assertNotEquals(signed, broken)
            val other = UUID.randomUUID()
            val brokenUrl = assertNotNull(server.register(other, file, broken, 4L shl 20))
            assertContentEquals(head.copyOfRange(0, 65_536), viaProxy(brokenUrl, 0, 65_535))
            assertTrue(server.totalBufferedBytes <= 2 * (64L shl 20))

            ffmpegDecodes(url)
        }
    }

    /**
     * With `DREAMDISPLAYS_FFMPEG` set to an `ffmpeg` binary, decodes a few seconds from the middle of
     * the file through the buffer with the player's own HTTP options (keep-alive, reconnect, seek
     * before open), the same libav HTTP client the in-process decoder uses.
     */
    private fun ffmpegDecodes(url: String) {
        val ffmpeg = System.getenv("DREAMDISPLAYS_FFMPEG")?.takeIf { it.isNotBlank() } ?: return
        val command = listOf(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostats",
            "-protocol_whitelist", "https,tls,tcp,crypto,data,http,httpproxy",
            "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10",
            "-reconnect_on_network_error", "1", "-reconnect_on_http_error", "5xx", "-multiple_requests", "1",
            "-rw_timeout", "15000000", "-probesize", "1M", "-analyzeduration", "1000000",
            "-ss", "1800", "-i", url, "-t", "4", "-f", "null", "-",
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "ffmpeg did not finish.")
        assertEquals(0, process.exitValue(), "ffmpeg failed through the buffer: $output")
        println("ffmpeg decoded 4 s at 30:00 through the buffer.")
    }
}
