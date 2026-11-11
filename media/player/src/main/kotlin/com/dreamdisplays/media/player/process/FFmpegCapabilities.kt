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

    private val liveStampsCache = ConcurrentHashMap<String, Boolean>()

    /**
     * True when [ffmpeg] can write per-frame mux stats (`-stats_mux_pre`, FFmpeg 6.1+), which live sessions use for
     * share latency (see [com.dreamdisplays.media.player.pipeline.LiveFrameStamps]). An older system FFmpeg rejects the
     * whole command line ("Unrecognized option"), so the stamps are only asked of a binary that lists the option.
     */
    fun hasLiveStamps(ffmpeg: String): Boolean =
        liveStampsCache.computeIfAbsent(ffmpeg) { bin ->
            val output = listing(bin, "-h", "long") ?: return@computeIfAbsent false
            listsOption(output, "stats_mux_pre").also { if (!it) logger.info("FFmpeg has no -stats_mux_pre; live frame stamps off.") }
        }

    private val encoderOptionCache = ConcurrentHashMap<String, Boolean>()

    /** True when [ffmpeg]'s [encoder] has option [name] (`-h encoder=<encoder>`, read once per binary and encoder). */
    fun encoderHasOption(ffmpeg: String, encoder: String, name: String): Boolean =
        encoderOptionCache.computeIfAbsent("$ffmpeg|$encoder|$name") {
            listing(ffmpeg, "-h", "encoder=$encoder")?.let { listsOption(it, name) } ?: false
        }

    /** True when FFmpeg's help [output] lists option [name] (as `-name` at the start of a line). */
    internal fun listsOption(output: String, name: String): Boolean =
        output.lineSequence().any { line ->
            val t = line.trimStart()
            t.startsWith("-$name") && t.getOrNull(name.length + 1).let { it == null || it == ' ' || it == '[' || it == '\t' }
        }

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

    private fun listing(bin: String, vararg flag: String): String? = runCatching {
        val proc = ProcessBuilder(bin, "-hide_banner", *flag).redirectErrorStream(true).start()
        runCatching { proc.outputStream.close() }
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            logger.warn("FFmpeg ${flag.joinToString(" ")} probe timed out.")
            return null
        }
        output
    }.getOrElse { e ->
        logger.warn("FFmpeg ${flag.joinToString(" ")} probe failed (${e.message}).")
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
