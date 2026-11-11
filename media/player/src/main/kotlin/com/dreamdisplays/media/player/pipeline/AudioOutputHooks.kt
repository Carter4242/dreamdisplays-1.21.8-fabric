package com.dreamdisplays.media.player.pipeline

import org.slf4j.LoggerFactory
import java.util.*
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * Lets the game decide where display audio goes and how loud it plays overall. The player opens its own Java Sound
 * line, which by default lands on the operating system's default device and ignores the game's sound settings; the
 * game points these hooks at its selected output device and master volume so display audio follows them.
 */
object AudioOutputHooks {
    private val logger = LoggerFactory.getLogger(AudioOutputHooks::class.java)

    /** Name of the output device the game uses (OpenAL style, e.g. "OpenAL Soft on Headphones (X)"); blank = default. */
    @Volatile
    var preferredDeviceName: () -> String? = { null }

    /** Overall volume multiplier from the game (its master volume), 0..1. */
    @Volatile
    var masterVolume: () -> Double = { 1.0 }

    @Volatile
    private var lastLogged: String? = null

    /** Current [masterVolume], guarded so a failing provider can never silence or break playback. */
    fun masterVolumeOrFull(): Double = runCatching { masterVolume() }.getOrDefault(1.0)
        .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0

    /** Opens a line for [info] on the game's output device when it can be matched, else on the default device. */
    fun getLine(info: DataLine.Info): SourceDataLine {
        val wanted = runCatching { preferredDeviceName() }.getOrNull()?.trim().orEmpty()
        val mixerInfo = if (wanted.isEmpty()) null else runCatching { match(wanted, info) }.getOrNull()
        if (mixerInfo != null) {
            val line = runCatching { AudioSystem.getMixer(mixerInfo).getLine(info) as SourceDataLine }.getOrNull()
            if (line != null) {
                logOnce("Display audio follows the game's device \"$wanted\" -> \"${mixerInfo.name}\".")
                return line
            }
        } else if (wanted.isNotEmpty()) {
            logOnce("Display audio: no output matches the game's device \"$wanted\"; using the system default.")
        }
        return AudioSystem.getLine(info) as SourceDataLine
    }

    /**
     * Output devices to try, best first: `preferred` is the game's device (when it can be matched) followed by the
     * system default (`null`); `other` is every remaining device that can play a stream, for when neither of those
     * opens at all.
     */
    fun candidateMixers(): Pair<List<Mixer.Info?>, List<Mixer.Info>> {
        val outputs = runCatching {
            AudioSystem.getMixerInfo().filter { info ->
                runCatching { AudioSystem.getMixer(info).isLineSupported(ANY_SOURCE_LINE) }.getOrDefault(false)
            }
        }.getOrDefault(emptyList())
        val wanted = runCatching { preferredDeviceName() }.getOrNull()?.trim().orEmpty()
        val matched = if (wanted.isEmpty()) null else bestMatch(wanted, outputs.map { it.name to it })
        if (matched != null) logOnce("Display audio follows the game's device \"$wanted\" -> \"${matched.name}\".")
        else if (wanted.isNotEmpty()) logOnce("Display audio: no output matches the game's device \"$wanted\"; using the system default.")
        val preferred = listOfNotNull(matched) + listOf<Mixer.Info?>(null)
        return preferred to outputs.filter { it != matched }
    }

    private val ANY_SOURCE_LINE = Line.Info(SourceDataLine::class.java)

    private fun logOnce(message: String) {
        if (lastLogged == message) return
        lastLogged = message
        logger.info(message)
    }

    private fun match(wanted: String, info: DataLine.Info): Mixer.Info? =
        bestMatch(wanted, AudioSystem.getMixerInfo().filter { runCatching { AudioSystem.getMixer(it).isLineSupported(info) }.getOrDefault(false) }
            .map { it.name to it })

    /**
     * Picks the Java Sound output whose name matches [wanted] (the game's OpenAL device name). OpenAL prefixes names
     * with "OpenAL Soft on ", and Java Sound can cut long names short, so a prefix match in either direction counts;
     * the longest matching name wins. Null when nothing matches.
     */
    internal fun <T> bestMatch(wanted: String, candidates: List<Pair<String, T>>): T? {
        val target = normalize(wanted.removePrefix("OpenAL Soft on "))
        if (target.isEmpty()) return null
        var best: Pair<String, T>? = null
        for ((rawName, value) in candidates) {
            val name = normalize(rawName)
            if (name.length < 4) continue
            val matches = name == target || target.startsWith(name) || name.startsWith(target)
            if (matches && (best == null || name.length > normalize(best.first).length)) best = rawName to value
        }
        return best?.second
    }

    private fun normalize(s: String): String = s.trim().lowercase(Locale.ROOT)
}
