package com.dreamdisplays.core.protocol.common

/**
 * Reading the client build id a [com.dreamdisplays.core.protocol.common.packets.ClientHello] carries
 * (`1a2b3c4d`, `1a2b3c4d-dev`, `1a2b3c4d-dev-dirty`, `unknown`; empty from jars that predate it).
 */
object ClientBuild {
    /** Accepted for `[clients] latest_build`: a commit (8+ hex digits), optionally with the dev suffixes. */
    val LATEST_PATTERN = Regex("[0-9A-Fa-f]{7,40}(-dev(-dirty)?)?")

    /** The reported id made safe to print (client-controlled), or null when the client sent none. */
    fun of(buildCommit: String?): String? =
        buildCommit?.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }?.take(40)?.lowercase()?.takeIf { it.isNotEmpty() }

    /** A configured latest build, cut to the same 8-digit form clients report; empty = not set. */
    fun normalizeLatest(latest: String): String {
        val trimmed = latest.trim().lowercase()
        if (trimmed.isEmpty()) return ""
        val hex = trimmed.takeWhile { it in 'a'..'f' || it in '0'..'9' }
        return hex.take(8) + trimmed.removePrefix(hex)
    }

    /** True when a latest build is configured and [buildCommit] is not it (older jars count as differing). */
    fun differs(buildCommit: String?, latest: String): Boolean {
        val want = normalizeLatest(latest)
        if (want.isEmpty()) return false
        return of(buildCommit) != want
    }
}
