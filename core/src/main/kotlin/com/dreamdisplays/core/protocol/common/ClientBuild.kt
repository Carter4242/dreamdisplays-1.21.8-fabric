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

    /** The commit part of a build id (`1a2b3c4d` of `1a2b3c4d-dev-dirty`), or null when it has none (`unknown`). */
    fun commitOf(build: String?): String? =
        build?.trim()?.lowercase()?.takeWhile { it in 'a'..'f' || it in '0'..'9' }?.take(8)?.takeIf { it.length >= 7 }

    /**
     * The build clients are expected to run: `[clients] latest_build` when set, else the server plugin's own build
     * [serverBuild] (both clients and server are built from the same commits); empty when neither is known.
     */
    fun expected(latest: String, serverBuild: String?): String =
        normalizeLatest(latest).ifEmpty { serverBuild?.let(::of)?.takeIf { commitOf(it) != null }.orEmpty() }

    /**
     * True when an [expected] build is known and [buildCommit] is from another commit. The `-dev` / `-dirty` suffixes
     * are ignored, so a local build of the same commit matches; jars without a build id always differ.
     */
    fun differs(buildCommit: String?, expected: String): Boolean {
        val want = commitOf(normalizeLatest(expected)) ?: return false
        return commitOf(of(buildCommit)) != want
    }
}
