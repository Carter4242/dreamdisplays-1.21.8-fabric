package com.dreamdisplays.util

import java.time.Instant
import java.util.Properties

/**
 * Which build this jar is, from `dreamdisplays-build.properties` (written at build time, client jars only).
 * [commit] is the 8-digit commit (`1a2b3c4d`), suffixed `-dev` / `-dev-dirty` for local builds, or `unknown`.
 */
object BuildInfo {
    private const val RESOURCE = "/dreamdisplays-build.properties"

    private val props: Properties by lazy {
        Properties().apply {
            runCatching { BuildInfo::class.java.getResourceAsStream(RESOURCE)?.use { load(it) } }
        }
    }

    /** Commit id of this build, or `unknown`. */
    val commit: String get() = props.getProperty("commit")?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"

    /** ISO-8601 build time, or empty when unknown. */
    val builtAt: String get() = props.getProperty("builtAt")?.trim().orEmpty()

    /** [builtAt] as epoch milliseconds, or 0 when unknown. */
    val builtAtEpochMs: Long get() = runCatching { Instant.parse(builtAt).toEpochMilli() }.getOrDefault(0L)
}
