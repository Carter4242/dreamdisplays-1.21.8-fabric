package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Experimental
import java.net.URI
import java.util.*

/**
 * Real-time relay URLs (`srt://`, `rtmp://`, `rtmps://`, `rtsp://`): a live picture pushed to a relay such as
 * MediaMTX and pulled from it with well under a second of delay. They are always live, never seekable, and are
 * played straight by `FFmpeg`: no extractor, no HTTP probe, no read-ahead.
 *
 * @since 1.9.x
 */
@Experimental
object LiveStreamUrls {
    /** Every scheme recognized as a live relay. */
    val SCHEMES: Set<String> = setOf("srt", "rtmp", "rtmps", "rtsp")

    /** Default SRT receive latency in ms when neither the URL nor the server sets one. */
    const val DEFAULT_SRT_LATENCY_MS: Int = 80

    /** Lowest SRT latency accepted, in ms: below this loss recovery has no time to resend anything. */
    const val MIN_SRT_LATENCY_MS: Int = 20

    /** Highest SRT latency accepted, in ms. */
    const val MAX_SRT_LATENCY_MS: Int = 5_000

    private val SRT_LATENCY_KEYS = ["latency", "rcvlatency", "peerlatency"]

    /** Lower-case scheme of [url] when it is a live relay URL, else null. */
    fun schemeOf(url: String): String? {
        val s = url.trim()
        val colon = s.indexOf("://")
        if (colon <= 0) return null
        return s.substring(0, colon).lowercase(Locale.ROOT).takeIf { it in SCHEMES }
    }

    /** True when [url] uses a live relay scheme (syntax only; see [isValid]). */
    fun isLive(url: String): Boolean = schemeOf(url) != null

    /**
     * True when [input] is a well-formed relay URL: a live scheme, a host, no whitespace or control characters,
     * within [maxLength].
     */
    fun isValid(input: String?, maxLength: Int = Int.MAX_VALUE): Boolean = parse(input, maxLength) != null

    /** Parses [input] as a relay URL, or null when it is not one (see [isValid]). */
    fun parse(input: String?, maxLength: Int = Int.MAX_VALUE): URI? {
        val value = input?.trim() ?: return null
        if (value.isEmpty() || value.length > maxLength) return null
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        if (schemeOf(value) == null) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    /** Host of [url] in lower case without IPv6 brackets, or null. */
    fun hostOf(url: String): String? =
        parse(url)?.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /**
     * Title for a relay URL: the SRT `streamid` path (`read:room1` -> `room1`), else the URL path (`/live/room1` ->
     * `live/room1`), else the host.
     */
    fun displayName(url: String): String {
        val uri = parse(url) ?: return url
        queryParam(uri.rawQuery, "streamid")?.let { id ->
            // MediaMTX shapes: "read:<path>[:user:pass]" and "#!::r=<path>,m=request"
            val parts = id.split(':')
            val name = when {
                id.startsWith("#!::") -> id.removePrefix("#!::").split(',')
                    .firstOrNull { it.startsWith("r=") }?.removePrefix("r=")

                parts.size >= 2 && parts[0].lowercase(Locale.ROOT) in setOf("read", "publish") -> parts[1]
                else -> id
            }?.trim()?.trim('/')
            if (!name.isNullOrEmpty()) return name
        }
        val path = uri.path.orEmpty().trim('/')
        return path.ifEmpty { uri.host }
    }

    /**
     * The URL `FFmpeg` should open for [url]. SRT gets a receive latency (the URL's own `latency` /
     * `rcvlatency` / `peerlatency` when given, else [latencyMs], else [DEFAULT_SRT_LATENCY_MS]) and
     * `transtype=live`. `FFmpeg` takes SRT latencies in microseconds, so a small value that can only have been
     * meant as milliseconds (at most [MAX_SRT_LATENCY_MS]) is converted. Other schemes pass through unchanged.
     */
    fun playUrl(url: String, latencyMs: Int? = null): String {
        val trimmed = url.trim()
        if (schemeOf(trimmed) != "srt") return trimmed
        val base = trimmed.substringBefore('?')
        val params = trimmed.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.toMutableList()
        var hasLatency = false
        for (i in params.indices) {
            val key = params[i].substringBefore('=').lowercase(Locale.ROOT)
            if (key !in SRT_LATENCY_KEYS) continue
            hasLatency = true
            val raw = params[i].substringAfter('=', "").toLongOrNull() ?: continue
            params[i] = "$key=${latencyMicros(raw)}"
        }
        if (!hasLatency) {
            val ms = (latencyMs ?: DEFAULT_SRT_LATENCY_MS).coerceIn(MIN_SRT_LATENCY_MS, MAX_SRT_LATENCY_MS)
            params += "latency=${ms * 1_000L}"
        }
        if (params.none { it.substringBefore('=').equals("transtype", ignoreCase = true) }) params += "transtype=live"
        return "$base?${params.joinToString("&")}"
    }

    /**
     * The URL a publisher should push to for [url]: [playUrl]'s latency handling, plus SRT's `pkt_size=1316` (seven
     * MPEG-TS packets, the size SRT relays expect) when absent.
     */
    fun publishUrl(url: String, latencyMs: Int? = null): String {
        val play = playUrl(url, latencyMs)
        if (schemeOf(play) != "srt" || play.contains("pkt_size=", ignoreCase = true)) return play
        return "$play&pkt_size=1316"
    }

    /**
     * The URL for stream [id] from a `[live]` relay URL [template]: every `{id}` replaced; scheme, host, port and any
     * query (such as MediaMTX `?user=...&pass=...`) kept exactly as configured.
     */
    fun fromTemplate(template: String, id: String): String = template.trim().replace("{id}", id)

    /**
     * [text] with every credential a URL in it may carry replaced by `<redacted>`: the `user:pass@` part of any
     * `scheme://` URL, the `user=` / `pass=` / `jwt=` / `passphrase=` query parameters (MediaMTX publish credentials,
     * SRT encryption), and the `user:pass` tail of a MediaMTX `streamid=publish:<path>:<user>:<pass>`. For log lines,
     * chat, command replies and error reports; everything else is left as is.
     */
    fun redactCredentials(text: String): String {
        if (!text.contains("://") && !text.contains('=')) return text
        var out = USERINFO.replace(text) { m -> "${m.groupValues[1]}$REDACTED@" }
        out = SECRET_PARAM.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
        out = STREAM_ID.replace(out) { m ->
            val value = m.groupValues[2]
            val parts = value.split(STREAM_ID_SEPARATOR)
            if (parts.size < 4 || parts[0].lowercase(Locale.ROOT) !in setOf("read", "publish")) m.value
            else {
                // Keep "<action>:<path>" with the separator the URL used (plain or percent-encoded)
                val kept = value.substring(0, value.indexOf(parts[1], parts[0].length) + parts[1].length)
                "${m.groupValues[1]}$kept:$REDACTED"
            }
        }
        return out
    }

    private const val REDACTED = "<redacted>"

    /** `scheme://user[:pass]@`: the userinfo of any URL. */
    private val USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s/?#@"'<>]+@""")

    /** Credential query parameters: MediaMTX `user` / `pass` / `jwt`, SRT `passphrase`, generic passwords. */
    private val SECRET_PARAM = Regex("""(?i)([?&](?:passphrase|password|passwd|pass|pwd|user|username|jwt|token)=)(?!<redacted>)[^&\s"'<>#]*""")

    /** A `streamid` query parameter (its credentials, if any, are its third and later `:` parts). */
    private val STREAM_ID = Regex("""(?i)([?&]streamid=)([^&\s"'<>#]*)""")
    private val STREAM_ID_SEPARATOR = Regex("""(?i):|%3A""")

    /** A URL SRT latency in microseconds: a value up to [MAX_SRT_LATENCY_MS] is read as ms; clamped to the bounds. */
    internal fun latencyMicros(raw: Long): Long {
        val micros = if (raw <= MAX_SRT_LATENCY_MS) raw * 1_000L else raw
        return micros.coerceIn(MIN_SRT_LATENCY_MS * 1_000L, MAX_SRT_LATENCY_MS * 1_000L)
    }

    private fun queryParam(rawQuery: String?, name: String): String? =
        rawQuery?.split('&')
            ?.firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrDefault(it) }
            ?.takeIf { it.isNotEmpty() }
}
