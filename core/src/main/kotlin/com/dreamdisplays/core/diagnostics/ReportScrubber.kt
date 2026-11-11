package com.dreamdisplays.core.diagnostics

import com.dreamdisplays.api.media.source.LiveStreamUrls

/**
 * Removes private details from text a client reports to the server (error messages, `FFmpeg` / `yt-dlp` stderr,
 * tool paths): the user's home directory becomes `~`, cookie / authorization values are dropped, and signed stream
 * URLs keep their host and path but lose the query (signatures, tokens, the viewer's IP). URL credentials
 * (`user:pass@`, relay `user=` / `pass=` parameters, see [LiveStreamUrls.redactCredentials]) are always dropped.
 */
object ReportScrubber {
    private const val REDACTED = "<redacted>"

    /** `C:\Users\<name>` (also `/` separators, escaped `\\`, and `Documents and Settings`). */
    private val WINDOWS_HOME = Regex(
        // A name with spaces is taken up to the next separator; one at the end of a path stops at whitespace
        """(?i)\b[A-Z]:(?:\\\\|[\\/])+(?:Users|Documents and Settings)(?:\\\\|[\\/])+""" +
            """(?:[^\\/:*?"<>|\r\n]+?(?=[\\/])|[^\\/\s:*?"<>|\r\n]+)""",
    )

    /** `/Users/<name>` (macOS), `/home/<name>` and `/var/home/<name>` (Linux). */
    private val UNIX_HOME = Regex("""(?:/var)?/(?:Users|home)/[^/\s"':]+""")

    /** `/root` as a home directory. */
    private val ROOT_HOME = Regex("""(?<![\w.~-])/root(?=/|\s|$)""")

    /** Header-style secrets: `Cookie: ...`, `Authorization: Bearer ...`, also inside `--add-header` arguments. */
    private val SECRET_HEADER = Regex(
        """(?i)\b(cookie|set-cookie|authorization|proxy-authorization|x-goog-authuser|x-youtube-identity-token|x-goog-visitor-id)(\s*[:=]\s*)[^\r\n"']*""",
    )

    /** Command-line secrets: `--cookies <file>`, `--password <x>`, `--username <x>`, `--video-password <x>`. */
    private val SECRET_ARG = Regex("""(?i)(--(?:cookies|cookies-from-browser|password|username|video-password|ap-password|ap-username))(\s+|=)("[^"]*"|'[^']*'|\S+)""")

    private val URL = Regex("""(?i)\b(?:https?|srt|rtmps?|rtsp)://[^\s"'<>]+""")

    /** Query parameters that make a URL signed / personal; any of them drops the whole query. */
    private val SIGNED_PARAMS = setOf(
        "sig", "signature", "lsig", "token", "access_token", "auth", "authorization", "key", "pot", "ip", "ipbits",
        "expire", "expires", "x-amz-signature", "x-amz-credential", "x-goog-signature", "policy", "key-pair-id",
        "hdnts", "hdnea", "session", "sessionid", "sid", "password", "pass", "jwt",
    )

    /** Hosts whose URLs are always signed stream URLs. */
    private val SIGNED_HOSTS = listOf("googlevideo.com", "ttvnw.net", "akamaized.net", "cloudfront.net", "vimeocdn.com")

    /** Scrubs [text]; [homes] are further home directories to replace (the client's own `user.home`). */
    fun scrub(text: String, homes: Collection<String> = emptyList()): String {
        if (text.isEmpty()) return text
        var out = SECRET_HEADER.replace(LiveStreamUrls.redactCredentials(text)) { m -> "${m.groupValues[1]}${m.groupValues[2]}$REDACTED" }
        out = SECRET_ARG.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}$REDACTED" }
        out = URL.replace(out) { m -> scrubUrl(m.value) }
        for (home in homes) {
            val trimmed = home.trimEnd('/', '\\')
            if (trimmed.length >= 3) out = out.replace(trimmed, "~", ignoreCase = isWindowsPath(trimmed))
        }
        out = WINDOWS_HOME.replace(out, "~")
        out = UNIX_HOME.replace(out, "~")
        out = ROOT_HOME.replace(out, "~")
        return out
    }

    /** [url] with its query dropped when it is a signed stream URL; other URLs only lose sensitive parameters. */
    fun scrubUrl(rawUrl: String): String {
        val url = LiveStreamUrls.redactCredentials(rawUrl)
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val base = url.substring(0, queryStart)
        val fragmentStart = url.indexOf('#', queryStart)
        val query = if (fragmentStart < 0) url.substring(queryStart + 1) else url.substring(queryStart + 1, fragmentStart)
        val host = base.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val names = query.split('&').map { it.substringBefore('=').lowercase() }
        if (SIGNED_HOSTS.any { host == it || host.endsWith(".$it") } || names.any { it in SIGNED_PARAMS }) {
            return "$base?$REDACTED"
        }
        return url
    }

    /**
     * The last [maxLines] non-blank lines of [stderr], at most [maxBytes] UTF-8 bytes (older lines go first), already
     * [scrub]bed with [homes].
     */
    fun excerpt(stderr: String, maxLines: Int = 15, maxBytes: Int = 2048, homes: Collection<String> = emptyList()): String {
        if (stderr.isBlank()) return ""
        val lines = stderr.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList().takeLast(maxLines)
            .map { scrub(it, homes) }
        val kept = ArrayDeque<String>()
        var bytes = 0
        for (line in lines.asReversed()) {
            val size = line.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > maxBytes) {
                if (kept.isEmpty()) kept.addFirst(truncateBytes(line, maxBytes))
                break
            }
            kept.addFirst(line)
            bytes += size
        }
        return kept.joinToString("\n")
    }

    /** [text] cut to at most [maxBytes] UTF-8 bytes, keeping its end (where the error usually is). */
    fun truncateBytes(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var start = bytes.size - maxBytes
        // Never start inside a multi-byte sequence
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private fun isWindowsPath(path: String): Boolean = path.length >= 2 && path[1] == ':'
}
