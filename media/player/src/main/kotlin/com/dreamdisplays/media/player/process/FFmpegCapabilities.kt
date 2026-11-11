package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.FFmpegCapabilities.PROBED_FILTERS
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One-time probe of optional `FFmpeg` features (hardware scale filters etc.) for a given binary. Building the process's
 * filter list is expensive, so results are cached per path.
 */
internal object FFmpegCapabilities {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val filterCache = ConcurrentHashMap<String, Set<String>>()
    private val PROBED_FILTERS = setOf("scale_vt", "scale_cuda", "scale_vaapi")

    private val decoderCache = ConcurrentHashMap<String, Set<String>>()
    private val PROBED_DECODERS = setOf("libvpx-vp9", "libvpx")
    private val WHITESPACE = Regex("""\s+""")

    /** True when [ffmpeg] supports filter [name] (e.g. `scale_vt`). */
    fun hasFilter(ffmpeg: String, name: String): Boolean = probedFilters(ffmpeg).contains(name)

    /** True when [ffmpeg] has decoder [name]; only the optional ones in [PROBED_DECODERS] are probed. */
    fun hasDecoder(ffmpeg: String, name: String): Boolean = probedDecoders(ffmpeg).contains(name)

    /** The optional decoders [ffmpeg] has (probed once). */
    fun optionalDecoders(ffmpeg: String): Set<String> = probedDecoders(ffmpeg)

    /** The optional filters [ffmpeg] has (probed once). */
    fun optionalFilters(ffmpeg: String): Set<String> = probedFilters(ffmpeg)

    private fun probedDecoders(ffmpeg: String): Set<String> =
        decoderCache.computeIfAbsent(ffmpeg) { bin ->
            val output = listing(bin, "-decoders") ?: return@computeIfAbsent emptySet()
            parseDecoders(output, PROBED_DECODERS).also { logger.info("FFmpeg optional decoders available: $it.") }
        }

    /** Picks the [wanted] decoder names out of `ffmpeg -decoders` [output] (flags column, then the name). */
    internal fun parseDecoders(output: String, wanted: Set<String>): Set<String> =
        output.lineSequence()
            .mapNotNull { line -> line.trim().split(WHITESPACE).getOrNull(1) }
            .filterTo(HashSet()) { it in wanted }

    private fun listing(bin: String, flag: String): String? = runCatching {
        val proc = ProcessBuilder(bin, "-hide_banner", flag).redirectErrorStream(true).start()
        runCatching { proc.outputStream.close() }
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            logger.warn("FFmpeg $flag probe timed out.")
            return null
        }
        output
    }.getOrElse { e ->
        logger.warn("FFmpeg $flag probe failed (${e.message}).")
        null
    }

    private fun probedFilters(ffmpeg: String): Set<String> =
        filterCache.computeIfAbsent(ffmpeg) { bin ->
            runCatching {
                val proc = ProcessBuilder(bin, "-hide_banner", "-filters")
                    .redirectErrorStream(true)
                    .start()
                runCatching { proc.outputStream.close() }
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    logger.warn("FFmpeg -filters probe timed out; assuming no optional filters.")
                    return@computeIfAbsent emptySet()
                }
                // Each line lists the filter name as a whitespace-delimited column.
                PROBED_FILTERS.filterTo(HashSet()) { f -> output.contains(" $f ") }
                    .also { logger.info("FFmpeg optional filters available: $it.") }
            }.getOrElse { e ->
                logger.warn("FFmpeg -filters probe failed (${e.message}); assuming no optional filters.")
                emptySet()
            }
        }
}
