package com.dreamdisplays.core.display.policy

import java.util.Locale

/**
 * The admin-facing names of every [DisplayPolicy] field: `section.field` in the config's snake_case
 * (`audio.radius_blocks`), mapped to the policy's own field path (`audio.radiusBlocks`), its type and its
 * allowed range. Used by commands and the config writer so both speak the same keys as `config.toml`.
 */
object PolicyPaths {
    /** Value type of a policy field; [parse] turns command / config text into the matching value. */
    sealed class Kind {
        abstract fun parse(raw: String): Any?

        data object Bool : Kind() {
            override fun parse(raw: String): Any? = when (raw.lowercase(Locale.ROOT)) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> null
            }
        }

        data class Whole(val min: Long, val max: Long) : Kind() {
            override fun parse(raw: String): Any? = raw.toLongOrNull()?.takeIf { it in min..max }?.toInt()
        }

        data class Decimal(val min: Double, val max: Double) : Kind() {
            override fun parse(raw: String): Any? =
                raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= min && it <= max }
        }

        data class Choice(val values: List<String>) : Kind() {
            override fun parse(raw: String): Any? = raw.lowercase(Locale.ROOT).takeIf { it in values }
        }

        /** Free text matching [pattern]; [description] is shown as the hint. */
        class Text(val pattern: Regex, val description: String) : Kind() {
            override fun parse(raw: String): Any? = raw.takeIf { pattern.matches(it) }
        }
    }

    /** One settable field. [get] reads it from a policy (null = unset). */
    class Path(val name: String, val field: String, val kind: Kind, val get: (DisplayPolicy) -> Any?) {
        /** Only settable server-wide (`/display config`), not as one display's override. */
        val serverWideOnly: Boolean get() = this.field in DisplayPolicy.GLOBAL_ONLY

        /** Range or choices, for help / error text. */
        val hint: String
            get() = when (kind) {
                Kind.Bool -> "true|false"
                is Kind.Whole -> "${kind.min}..${kind.max}"
                is Kind.Decimal -> "${fmt(kind.min)}..${fmt(kind.max)}"
                is Kind.Choice -> kind.values.joinToString("|")
                is Kind.Text -> kind.description
            }
    }

    val all: List<Path> = listOf(
        Path("sync.enabled", "sync.enabled", Kind.Bool) { it.sync?.enabled },
        Path("sync.dead_band_ms", "sync.deadBandMs", Kind.Whole(0, 1_000)) { it.sync?.deadBandMs },
        Path("sync.max_rate_pct", "sync.maxRatePct", Kind.Decimal(0.0, 5.0)) { it.sync?.maxRatePct },
        Path("sync.seek_threshold_ms", "sync.seekThresholdMs", Kind.Whole(200, 60_000)) { it.sync?.seekThresholdMs },
        Path("sync.seek_cooldown_ms", "sync.seekCooldownMs", Kind.Whole(1_000, 600_000)) { it.sync?.seekCooldownMs },
        Path("audio.radius_blocks", "audio.radiusBlocks", Kind.Decimal(0.0, 4_096.0)) { it.audio?.radiusBlocks },
        Path("audio.full_volume_blocks", "audio.fullVolumeBlocks", Kind.Decimal(0.0, 4_096.0)) {
            it.audio?.fullVolumeBlocks
        },
        Path("audio.falloff", "audio.falloff", Kind.Choice(listOf("linear", "squared"))) {
            it.audio?.falloff?.name?.lowercase(Locale.ROOT)
        },
        Path("audio.start_volume", "audio.startVolume", Kind.Decimal(0.0, 1.0)) { it.audio?.startVolume },
        Path("audio.max_volume", "audio.maxVolume", Kind.Decimal(0.0, 2.0)) { it.audio?.maxVolume },
        Path("visibility.max_distance_blocks", "visibility.maxDistanceBlocks", Kind.Decimal(0.0, 4_096.0)) {
            it.visibility?.maxDistanceBlocks
        },
        Path("quality.max_height", "quality.maxHeight", Kind.Whole(144, 4_320)) { it.quality?.maxHeight },
        Path("client.enabled", "client.enabled", Kind.Bool) { it.client?.enabled },
        Path("client.max_active_displays", "client.maxActiveDisplays", Kind.Whole(0, 16)) {
            it.client?.maxActiveDisplays
        },
        Path("client.hardware_decoding", "client.hardwareDecoding", Kind.Bool) { it.client?.hardwareDecoding },
        Path("client.audio_acoustics", "client.audioAcoustics", Kind.Choice(listOf("off", "basic", "advanced", "ultra"))) {
            it.client?.audioAcoustics?.name?.lowercase(Locale.ROOT)
        },
        Path("client.unshaded_displays", "client.unshadedDisplays", Kind.Bool) { it.client?.unshadedDisplays },
        Path("client.display_distance_chunks", "client.displayDistanceChunks", Kind.Whole(0, 32)) {
            it.client?.displayDistanceChunks
        },
        Path("client.audio_output_profile", "client.audioOutputProfile", Kind.Choice(listOf("headphones", "speakers"))) {
            it.client?.audioOutputProfile?.name?.lowercase(Locale.ROOT)
        },
        Path("client.audio_normalization", "client.audioNormalization", Kind.Bool) { it.client?.audioNormalization },
        Path("debug.sync_log_interval_seconds", "debug.syncLogIntervalSeconds", Kind.Whole(0, 3_600)) {
            it.debug?.syncLogIntervalSeconds
        },
        Path("debug.verbose", "debug.verbose", Kind.Bool) { it.debug?.verbose },
        Path("debug.report_interval_seconds", "debug.reportIntervalSeconds", Kind.Whole(0, 600)) {
            it.debug?.reportIntervalSeconds
        },
        Path("debug.log_level", "debug.logLevel", Kind.Choice(ClientLogLevel.entries.map { it.wire })) {
            it.debug?.logLevel
        },
        Path("network.read_ahead_seconds", "network.readAheadSeconds", Kind.Whole(0, 120)) {
            it.network?.readAheadSeconds
        },
        Path("network.live_latency_ms", "network.liveLatencyMs", Kind.Whole(20, 5_000)) {
            it.network?.liveLatencyMs
        },
    )

    private val byName = all.associateBy { it.name }

    /** The path called [name] (case-insensitive), or null. */
    fun find(name: String): Path? = byName[name.lowercase(Locale.ROOT)]

    /**
     * [base] with [path] set from the text [raw] (or cleared when [raw] is null). Returns null when the text
     * does not parse or is out of range for that field.
     */
    fun apply(base: DisplayPolicy?, path: Path, raw: String?): DisplayPolicy? {
        if (raw == null) return DisplayPolicy.withField(base, path.field, null)
        val value = path.kind.parse(raw.trim()) ?: return null
        return DisplayPolicy.withField(base, path.field, value)
    }

    /** Readable form of a policy value. */
    fun format(value: Any?): String = when (value) {
        null -> "-"
        is Double -> fmt(value)
        else -> value.toString()
    }

    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
}
