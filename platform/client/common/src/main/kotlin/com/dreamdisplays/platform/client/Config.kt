package com.dreamdisplays.platform.client

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.media.source.youtube.cookie.CookieSource
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

/** Client configuration loaded from and persisted to `config.yml`. */
class Config(private val baseDir: File) {
    private val file = File(baseDir, "config.yml")
    private val logger = LoggerFactory.getLogger(Config::class.java)

    /** Whether to mute all displays while the game window is not focused. */
    var muteOnAltTab: Boolean = false

    /** Max radius (blocks) for audio distance falloff; display visibility itself follows the client's own render distance option. */
    var defaultDistance: Int = 96

    /** Default volume for new displays, in range `0.0`..`1.0`. */
    var defaultDisplayVolume: Double = 0.5

    /**
     * The settings screen's "Dream Displays" switch: false stops everything the mod does on this client (see
     * [MasterSwitch]) until the player turns it back on. No server changes it.
     */
    var modEnabled: Boolean = true

    /** Whether displays are enabled at all (on every server; a server's own switch is [displaysDisabledServers]). */
    var displaysEnabled: Boolean = true

    /** Servers (address, lowercase) that switched displays off for this player (`/display off`); see [DisplaysSwitch]. */
    var displaysDisabledServers: Set<String> = emptySet()

    /** Browser to import `yt-dlp` cookies from, or [CookieSource.NONE] to disable. */
    var ytdlpCookieSource: CookieSource = CookieSource.NONE

    /** Proxy URL passed to `yt-dlp`, or empty for a direct connection. */
    var ytdlpProxy: String = ""

    /** Whether to use hardware-accelerated video decoding. */
    var useHwAccel: Boolean = true

    var unshadedDisplays: Boolean = true

    /**
     * Default 3D acoustics rendering tier (`off` / `basic` / `advanced` / `ultra`), for every display the player has not
     * set its own tier on (its menu slider).
     */
    var audioAcoustics: AcousticQuality = AcousticQuality.ADVANCED

    /** Output profile for spatialized audio: `true` renders binaural for headphones, `false` a plain stereo pan for speakers. */
    var audioBinauralOutput: Boolean = true

    /** Whether every display's audio is brought to one loudness, so quiet and loud videos need no volume change. */
    var audioNormalization: Boolean = true

    /** Volume a display starts at the first time it is seen, `0.0`..`1.0` (`1.0` is 200% in the menu); a server default wins over it. */
    var newDisplayVolume: Double = DEFAULT_NEW_DISPLAY_VOLUME

    /** Quality a display starts at the first time it is seen: `auto` or a height such as `1080`. */
    var newDisplayQuality: String = DEFAULT_NEW_DISPLAY_QUALITY

    /** Farthest displays stay alive, in chunks; `0` follows the render and simulation distance. */
    var displayDistanceChunks: Int = 0

    /** How many displays may play at once, nearest first; `0` is no limit. */
    var maxActiveDisplays: Int = 0

    /**
     * Display audio output: `auto` (Java Sound, OpenAL when Java Sound cannot play), `javasound` or `openal`. The
     * `dreamdisplays.audioBackend` system property wins over it.
     */
    var audioBackend: String = "auto"

    /**
     * The file exists but could not be read at start (another program held it, e.g. an antivirus scan or a sync tool):
     * the values in memory were defaults then, as saved here. The next save merges instead of overwriting: a setting
     * changed since keeps its new value, every other one the file's ([ConfigWrites.merge]); without that the defaults
     * would replace the player's settings, and the servers they switched displays off on.
     */
    @Volatile
    private var unreadDefaults: String? = null

    init {
        load()
    }

    /** Re-reads values from disk, replacing any in-memory state. */
    fun reload() = load()

    private fun load() {
        if (!file.exists()) {
            save(); return
        }
        // Unreadable (locked by another program): a few short retries, then keep the current values rather than fail
        // the mod's start, and remember them so the next save does not write them over the file
        val lines = readWithRetries() ?: run {
            if (unreadDefaults == null) unreadDefaults = render()
            return
        }
        unreadDefaults = null
        val data = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null
                else line.substring(0, colon).trim() to
                        line.substring(colon + 1).trim().removeSurrounding("'").removeSurrounding("\"")
            }
            .toMap()

        muteOnAltTab = data["mute-on-alt-tab"]?.toBooleanStrictOrNull() ?: muteOnAltTab
        val rawDistance = data["default-render-distance"]?.toIntOrNull() ?: defaultDistance
        defaultDistance = ((rawDistance / 16.0).roundToInt().coerceIn(2, 12)) * 16
        defaultDisplayVolume = data["default-default-display-volume"]?.toDoubleOrNull() ?: defaultDisplayVolume
        modEnabled = data["mod-enabled"]?.toBooleanStrictOrNull() ?: modEnabled
        displaysEnabled = data["displays-enabled"]?.toBooleanStrictOrNull() ?: displaysEnabled
        displaysDisabledServers = data["displays-disabled-servers"]?.let(DisplaysSwitch::parseServers) ?: displaysDisabledServers
        ytdlpCookieSource = data["ytdlp-cookies-from-browser"]
            ?.let { CookieSource.fromConfig(it) }
            ?: ytdlpCookieSource
        ytdlpProxy = data["ytdlp-proxy"] ?: ytdlpProxy
        useHwAccel = data["use-hw-accel"]?.toBooleanStrictOrNull() ?: useHwAccel
        unshadedDisplays = data["unshaded-displays"]?.toBooleanStrictOrNull() ?: unshadedDisplays
        audioAcoustics = data["audio-acoustics"]?.let { token ->
            AcousticQuality.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
        } ?: audioAcoustics
        audioNormalization = data["audio-normalization"]?.toBooleanStrictOrNull() ?: audioNormalization
        newDisplayVolume = data["new-display-volume"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: newDisplayVolume
        newDisplayQuality = data["new-display-quality"]?.let { VideoQuality.parse(it).serialize() } ?: newDisplayQuality
        displayDistanceChunks = data["display-distance-chunks"]?.toIntOrNull()?.coerceIn(0, MAX_DISTANCE_CHUNKS)
            ?: displayDistanceChunks
        maxActiveDisplays = data["max-active-displays"]?.toIntOrNull()?.coerceIn(0, MAX_ACTIVE_DISPLAYS)
            ?: maxActiveDisplays
        audioBackend = data["audio-backend"]?.lowercase()?.takeIf { it in AUDIO_BACKENDS } ?: audioBackend
        audioBinauralOutput = when (data["audio-output-profile"]?.lowercase()) {
            "speakers" -> false
            "headphones", "auto" -> true
            else -> audioBinauralOutput
        }
    }

    private fun readWithRetries(): List<String>? {
        repeat(READ_ATTEMPTS) { attempt ->
            try {
                return file.readLines()
            } catch (e: IOException) {
                if (attempt == READ_ATTEMPTS - 1) {
                    logger.warn("Could not read ${file.path}: ${e.message}; it is not written until it can be read.")
                } else {
                    Thread.sleep(READ_RETRY_MS)
                }
            }
        }
        return null
    }

    /** Persists the current configuration values to disk. */
    fun save() {
        val defaults = unreadDefaults
        if (defaults != null && file.exists()) {
            val onDisk = try {
                file.readLines()
            } catch (e: IOException) {
                logger.warn("Not saving ${file.path}: it still cannot be read (${e.message}).")
                return
            }
            write(ConfigWrites.merge(defaults, render(), onDisk))
            unreadDefaults = null
            // The file's settings are this session's from now on
            load()
            return
        }
        unreadDefaults = null
        write(render())
    }

    /** The file's text for the current values. */
    private fun render(): String {
        return buildString {
            appendLine("mod-enabled: $modEnabled")
            appendLine("mute-on-alt-tab: $muteOnAltTab")
            appendLine("default-render-distance: $defaultDistance")
            appendLine("default-default-display-volume: $defaultDisplayVolume")
            appendLine("displays-enabled: $displaysEnabled")
            appendLine("displays-disabled-servers: ${DisplaysSwitch.formatServers(displaysDisabledServers).yamlQuoted()}")
            appendLine("ytdlp-cookies-from-browser: ${ytdlpCookieSource.configToken.yamlQuoted()}")
            appendLine("ytdlp-proxy: ${ytdlpProxy.yamlQuoted()}")
            appendLine("use-hw-accel: $useHwAccel")
            appendLine("unshaded-displays: $unshadedDisplays")
            appendLine("audio-acoustics: ${audioAcoustics.name.lowercase()}")
            appendLine("audio-output-profile: ${if (audioBinauralOutput) "headphones" else "speakers"}")
            appendLine("audio-normalization: $audioNormalization")
            appendLine("new-display-volume: $newDisplayVolume")
            appendLine("new-display-quality: $newDisplayQuality")
            appendLine("display-distance-chunks: $displayDistanceChunks")
            appendLine("max-active-displays: $maxActiveDisplays")
            appendLine("audio-backend: $audioBackend")
        }
    }

    private fun write(text: String) {
        // Written beside it and moved over it whole: a crash mid-write must not leave a cut-off file whose missing
        // keys (e.g. the servers that switched displays off) silently fall back to defaults
        try {
            baseDir.mkdirs()
            val tmp = File(baseDir, "config.yml.tmp")
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            logger.warn("Could not save ${file.path}: ${e.message}")
        }
    }

    companion object {
        /** Accepted `audio-backend` values. */
        val AUDIO_BACKENDS = setOf("auto", "javasound", "openal")

        const val DEFAULT_NEW_DISPLAY_VOLUME = 0.25
        const val DEFAULT_NEW_DISPLAY_QUALITY = "1080"
        const val MAX_DISTANCE_CHUNKS = 32
        const val MAX_ACTIVE_DISPLAYS = 16

        private const val READ_ATTEMPTS = 3
        private const val READ_RETRY_MS = 200L

        init {
            System.setProperty("file.encoding", "UTF-8")
        }

        private fun String.yamlQuoted(): String =
            if (isEmpty() || any { it in ":#{}[]|>&!*'\",\n\r\t" })
                "'${replace("'", "''")}'"
            else this
    }
}

/** Writing `config.yml` after it could not be read at start (see `Config.unreadDefaults`). */
internal object ConfigWrites {
    /**
     * The text to write: each setting of [current] (the file's text for the values in memory) as it is when it changed
     * since the failed read ([defaults], that text then), else as the file has it ([onDisk], its lines now).
     */
    fun merge(defaults: String, current: String, onDisk: List<String>): String {
        val before = linesByKey(defaults.lines())
        val disk = linesByKey(onDisk)
        return buildString {
            for (line in current.lines()) {
                if (line.isBlank()) continue
                val key = keyOf(line)
                val keep = key == null || line != before[key]
                appendLine(if (keep) line else disk[key] ?: line)
            }
        }
    }

    private fun keyOf(line: String): String? =
        line.trim().takeIf { it.isNotEmpty() && !it.startsWith('#') && ':' in it }?.substringBefore(':')?.trim()

    private fun linesByKey(lines: List<String>): Map<String, String> =
        lines.mapNotNull { line -> keyOf(line)?.let { it to line.trim() } }.toMap()
}
