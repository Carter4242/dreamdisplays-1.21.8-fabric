package com.dreamdisplays.media.player.readahead

import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.shared.policy.MediaHosts
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * [Upstream] over plain `HttpURLConnection`, which, unlike `java.net.http`, has a per-read timeout:
 * a host that stops sending mid-body fails the fetch instead of hanging it. Redirects are followed by
 * hand so every hop passes [MediaHostGuard] again.
 */
internal object HttpUpstream : Upstream {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5

    override fun open(url: String, from: Long, to: Long): UpstreamBody {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            MediaHostGuard.requireAllowed(target)
            val connection = (URI(target).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Range", "bytes=$from-$to")
                setRequestProperty("User-Agent", MediaProcess.USER_AGENT)
                // Ranged media must arrive byte-exact; a compressed body would break the offsets
                setRequestProperty("Accept-Encoding", "identity")
                MediaHosts.refererFor(target)?.let { setRequestProperty("Referer", it) }
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw IOException("Redirect without a location.")
                target = URI(target).resolve(location.trim()).toString()
                return@repeat
            }
            val total = connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: connection.getHeaderFieldLong("Content-Length", -1L).takeIf { status == 200 && it >= 0L }
            val stream = if (status in 200..299) connection.inputStream else null
            if (stream == null) connection.disconnect()
            return UpstreamBody(
                status = status,
                total = total,
                contentType = connection.contentType,
                stream = stream,
                onClose = { connection.disconnect() },
            )
        }
        throw IOException("Too many redirects.")
    }
}
