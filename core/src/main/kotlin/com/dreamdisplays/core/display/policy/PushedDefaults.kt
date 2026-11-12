package com.dreamdisplays.core.display.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Values an admin pushed onto every player's own settings for one display (`/display defaults`). Unlike [DisplayPolicy],
 * which the server holds in place, a pushed value is set on each player's client once, after which the player may change
 * it as usual; the server's policy levers (`audio.maxVolume`, `quality.maxHeight`, `client.audioAcoustics`) still apply
 * on top of it. Stored with the display and sent in its custom data under [KEY]; clients without
 * `ClientFeature.PUSHED_DEFAULTS` ignore it.
 *
 * Every push gets a new revision ([push]): one more than the display's highest so far, or the push time in epoch millis
 * when that is larger, so revisions keep rising across clears, restarts and an older copy of the display put back. A
 * client keeps, per display and setting, the revision it last applied, and applies a value only when its revision is
 * newer ([pending]): once per push, also for a player who first sees the display after it.
 */
@Serializable
data class PushedDefaults(
    /** Highest revision handed out on this display; kept after a clear, so later pushes are still newer. */
    val rev: Long = 0,
    /** The pushed values by [PushedSetting.wire] name. */
    val values: Map<String, Entry> = emptyMap(),
) {
    /** One pushed [value] (canonical text of its [PushedSetting]) and the [rev] it was pushed at. */
    @Serializable
    data class Entry(val value: String, val rev: Long)

    /** A value a client still has to apply: [rev] is what it records once it did. */
    data class Pending(val setting: PushedSetting, val value: PushedSetting.Value, val rev: Long)

    /** This with [setting] pushed as [value] (canonical, from [PushedSetting.parse]) at a new revision. */
    fun push(setting: PushedSetting, value: String, nowMillis: Long): PushedDefaults {
        val next = maxOf(rev + 1, nowMillis)
        return copy(rev = next, values = values + (setting.wire to Entry(value, next)))
    }

    /** This without [setting] (every setting when null): those are no longer pushed. The revision stays. */
    fun clear(setting: PushedSetting?): PushedDefaults =
        copy(values = if (setting == null) emptyMap() else values - setting.wire)

    /** The pushed value of [setting], or null. */
    operator fun get(setting: PushedSetting): Entry? = values[setting.wire]

    /**
     * What a client that applied the revisions in [applied] (setting wire name to revision) has to apply now: every
     * known setting pushed at a newer revision. Settings it does not know and values it cannot read are left out.
     */
    fun pending(applied: Map<String, Long>): List<Pending> = values.mapNotNull { (name, entry) ->
        val setting = PushedSetting.fromWire(name) ?: return@mapNotNull null
        if (entry.rev <= (applied[name] ?: 0L)) return@mapNotNull null
        val value = setting.decode(entry.value) ?: return@mapNotNull null
        Pending(setting, value, entry.rev)
    }

    /** Compact JSON for the custom-data entry. */
    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        /** Custom-data key the pushed values travel and are stored under. */
        const val KEY = "dd:defaults"

        /** Nothing pushed yet. */
        val EMPTY = PushedDefaults()

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

        /** Reads stored / received bytes; null when absent or unreadable. */
        fun decode(bytes: ByteArray?): PushedDefaults? {
            if (bytes == null || bytes.isEmpty()) return null
            return runCatching { json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()
        }
    }
}

/**
 * A personal per-display setting `/display defaults` can push. [wire] is its name in commands and on the wire. The
 * canonical text stored and sent for each ([parse]) is the client's own unit: volume and brightness as the 0..1 / 0..2
 * fractions the client saves, quality as its label, the 3D audio tier as a tier name (`default` = the client's default).
 */
enum class PushedSetting(val wire: String) {
    /** Volume as the display menu shows it, 0-200 % (the client saves 0..1: 100 % = 0.5). */
    VOLUME("volume") {
        override fun parse(raw: String): String? =
            percent(raw)?.takeIf { it in 0.0..200.0 }?.let { fmt(it / 200.0) }

        override fun decode(value: String): Value? =
            value.toFloatOrNull()?.takeIf { it.isFinite() }?.let { Value.Volume(it.coerceIn(0f, 1f)) }

        override fun show(value: String): String = value.toDoubleOrNull()?.let { "${(it * 200).roundToInt()}%" } ?: value
        override val hint = "0-200%"
        override val suggestions = listOf("50%", "100%", "150%", "200%")
    },

    /** Brightness as the display menu shows it, 0-100 % (the client saves 0..2: 100 % = 1.0). */
    BRIGHTNESS("brightness") {
        override fun parse(raw: String): String? =
            percent(raw)?.takeIf { it in 0.0..100.0 }?.let { fmt(it / 100.0) }

        override fun decode(value: String): Value? =
            value.toFloatOrNull()?.takeIf { it.isFinite() }?.let { Value.Brightness(it.coerceIn(0f, 2f)) }

        override fun show(value: String): String = value.toDoubleOrNull()?.let { "${(it * 100).roundToInt()}%" } ?: value
        override val hint = "0-100%"
        override val suggestions = listOf("50%", "75%", "100%")
    },

    /** Requested video quality: `auto` or a height from the menu's list (`720` or `720p`). */
    QUALITY("quality") {
        override fun parse(raw: String): String? {
            val t = raw.trim().lowercase(Locale.ROOT)
            if (t == AUTO) return AUTO
            return t.removeSuffix("p").toIntOrNull()?.takeIf { it in HEIGHTS }?.toString()
        }

        override fun decode(value: String): Value? = parse(value)?.let { Value.Quality(it) }
        override fun show(value: String): String = if (value == AUTO) AUTO else "${value}p"
        override val hint = "auto|${HEIGHTS.joinToString("|")}"
        override val suggestions = listOf(AUTO) + HEIGHTS.map { it.toString() }
    },

    /** The display's 3D audio tier (the menu's 3D audio slider); `default` follows the player's default tier. */
    AUDIO_3D("audio_3d") {
        override fun parse(raw: String): String? = raw.trim().lowercase(Locale.ROOT).takeIf { it in TIERS }

        override fun decode(value: String): Value? =
            parse(value)?.let { Value.Audio3d(if (it == DEFAULT_TIER) null else it.uppercase(Locale.ROOT)) }

        override fun show(value: String): String = value
        override val hint = TIERS.joinToString("|")
        override val suggestions = TIERS
    },

    /** The display's mute button. */
    MUTE("mute") {
        override fun parse(raw: String): String? = when (raw.trim().lowercase(Locale.ROOT)) {
            "on", "true", "yes", "muted" -> "true"
            "off", "false", "no", "unmuted" -> "false"
            else -> null
        }

        override fun decode(value: String): Value? = parse(value)?.let { Value.Mute(it == "true") }
        override fun show(value: String): String = if (value == "true") "on" else "off"
        override val hint = "on|off"
        override val suggestions = listOf("on", "off")
    };

    /** Canonical stored text for what an admin typed, or null when it is not a valid value. */
    abstract fun parse(raw: String): String?

    /** The client-side value of canonical [value], or null when it does not read (a newer or broken server). */
    abstract fun decode(value: String): Value?

    /** Canonical [value] the way the menu shows it (`100%`, `720p`). */
    abstract fun show(value: String): String

    /** Accepted values, for help and error text. */
    abstract val hint: String

    /** Values offered by tab completion. */
    abstract val suggestions: List<String>

    /** A pushed value as the client applies it. */
    sealed interface Value {
        /** The client's saved volume, 0..1. */
        data class Volume(val fraction: Float) : Value

        /** The client's saved brightness, 0..2. */
        data class Brightness(val level: Float) : Value

        /** A `VideoQuality` label (`auto` or a height). */
        data class Quality(val label: String) : Value

        /** An `AcousticQuality` name, or null to follow the client's default tier. */
        data class Audio3d(val tier: String?) : Value

        data class Mute(val muted: Boolean) : Value
    }

    companion object {
        /** The setting called [name] (command or wire name, case-insensitive), or null. */
        fun fromWire(name: String): PushedSetting? = entries.firstOrNull { it.wire.equals(name.trim(), ignoreCase = true) }
    }
}

// Outside the enum: its entries read these while the enum (and its companion) is still being set up
private const val AUTO = "auto"
private const val DEFAULT_TIER = "default"
private val HEIGHTS = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
private val TIERS = listOf("off", "basic", "advanced", "ultra", DEFAULT_TIER)

private fun percent(raw: String): Double? = raw.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() }

private fun fmt(d: Double): String {
    val rounded = (d * 10_000).roundToInt() / 10_000.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
}
