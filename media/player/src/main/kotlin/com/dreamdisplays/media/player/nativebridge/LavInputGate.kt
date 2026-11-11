package com.dreamdisplays.media.player.nativebridge

import java.net.URI

private val GIR_YES = Regex("[?&]gir=yes(?:&|$)")

/**
 * True when [url] may be handed to the in-process libav reader ([NativeMedia.lavOpen]). Its bounded-request path
 * (`native/lav/src/chunked.rs`) opens the URL with no protocol whitelist and picks it by a plain substring match on
 * `googlevideo.com/`, so the library (prebuilt, not patched here) must only ever see `http(s)`, and a URL that would
 * take that path must really be `https` on a googlevideo host. Anything else goes to the `FFmpeg` process instead.
 */
internal fun lavMayOpen(url: String): Boolean {
    val u = url.trim()
    val https = u.startsWith("https://", ignoreCase = true)
    if (!https && !u.startsWith("http://", ignoreCase = true)) return false
    val chunkable = u.contains("googlevideo.com/") && GIR_YES.containsMatchIn(u)
    if (!chunkable) return true
    val host = runCatching { URI(u).host?.lowercase() }.getOrNull() ?: return false
    return https && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
}
