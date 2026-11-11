package com.dreamdisplays.core.display.policy

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Server-controlled display behaviour, sent to clients in a display's custom data under [KEY].
 *
 * Every field is optional: `null` means "use the client's default". The server merges its config defaults
 * with a per-display override ([mergedOver]) and sends the result; the client reads it with [decode], which
 * tolerates unknown fields and drops any section it cannot read, so a newer or broken server never breaks
 * an older client (it just falls back to defaults).
 *
 * [v] is the schema major version. Adding fields or sections is compatible and keeps it; only a change that
 * gives an existing field a new meaning bumps it, and clients ignore a policy from a newer major.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DisplayPolicy(
    @EncodeDefault val v: Int = VERSION,
    val sync: Sync? = null,
    val audio: Audio? = null,
    val visibility: Visibility? = null,
    val quality: Quality? = null,
    val network: Network? = null,
) {
    /** Playback sync to the server timeline (Synced / Broadcast / watch parties). */
    @Serializable
    data class Sync(
        /** False turns gentle rate correction off; large drift is still corrected by seeking. */
        val enabled: Boolean? = null,
        /** Drift below this is left alone, in ms. */
        val deadBandMs: Int? = null,
        /** Largest playback-rate change used to close drift, in percent (1.0 = 0.99x .. 1.01x). */
        val maxRatePct: Double? = null,
        /** Drift above this is corrected with a seek instead of a rate change, in ms. */
        val seekThresholdMs: Int? = null,
        /** Minimum time between two corrective seeks, in ms. */
        val seekCooldownMs: Int? = null,
    ) {
        fun mergedOver(base: Sync?): Sync = Sync(
            enabled = enabled ?: base?.enabled,
            deadBandMs = deadBandMs ?: base?.deadBandMs,
            maxRatePct = maxRatePct ?: base?.maxRatePct,
            seekThresholdMs = seekThresholdMs ?: base?.seekThresholdMs,
            seekCooldownMs = seekCooldownMs ?: base?.seekCooldownMs,
        )
    }

    /** How a display's sound carries with distance, and the volume range players get. */
    @Serializable
    data class Audio(
        /** Distance in blocks at which the sound has faded to silence. */
        val radiusBlocks: Double? = null,
        /** Shape of the fade between [fullVolumeBlocks] and [radiusBlocks]. */
        val falloff: Falloff? = null,
        /** Volume (0..1) a player starts at the first time they see this display; they can change it after. */
        val startVolume: Double? = null,
        /** Highest volume a player can play this display at (0..2, 1 = 100 %). */
        val maxVolume: Double? = null,
        /** Within this many blocks the sound plays at full volume; fading starts beyond it. */
        val fullVolumeBlocks: Double? = null,
    ) {
        fun mergedOver(base: Audio?): Audio = Audio(
            radiusBlocks = radiusBlocks ?: base?.radiusBlocks,
            fullVolumeBlocks = fullVolumeBlocks ?: base?.fullVolumeBlocks,
            falloff = falloff ?: base?.falloff,
            startVolume = startVolume ?: base?.startVolume,
            maxVolume = maxVolume ?: base?.maxVolume,
        )
    }

    @Serializable
    enum class Falloff {
        @SerialName("linear")
        LINEAR,

        @SerialName("squared")
        SQUARED,
    }

    /** How far away a display is still loaded and played; the player's own render distance still applies. */
    @Serializable
    data class Visibility(val maxDistanceBlocks: Double? = null) {
        fun mergedOver(base: Visibility?): Visibility =
            Visibility(maxDistanceBlocks = maxDistanceBlocks ?: base?.maxDistanceBlocks)
    }

    /** Stream quality limits: the highest resolution (video height) clients pick for this display. */
    @Serializable
    data class Quality(val maxHeight: Int? = null) {
        fun mergedOver(base: Quality?): Quality = Quality(maxHeight = maxHeight ?: base?.maxHeight)
    }

    /** How clients fetch a display's media. */
    @Serializable
    data class Network(
        /** Seconds of a direct media file (MP4 / WebM link) kept downloaded ahead of playback; 0 turns it off. */
        val readAheadSeconds: Int? = null,
    ) {
        fun mergedOver(base: Network?): Network =
            Network(readAheadSeconds = readAheadSeconds ?: base?.readAheadSeconds)
    }

    /** True when no section sets anything, so there is nothing worth sending. */
    val isEmpty: Boolean
        get() = (sync == null || sync == Sync()) && (audio == null || audio == Audio()) &&
                (visibility == null || visibility == Visibility()) && (quality == null || quality == Quality()) &&
                (network == null || network == Network())

    /** This policy with every field it leaves unset taken from [base]. */
    fun mergedOver(base: DisplayPolicy): DisplayPolicy = DisplayPolicy(
        v = VERSION,
        sync = mergeSection(sync, base.sync, Sync::mergedOver),
        audio = mergeSection(audio, base.audio, Audio::mergedOver),
        visibility = mergeSection(visibility, base.visibility, Visibility::mergedOver),
        quality = mergeSection(quality, base.quality, Quality::mergedOver),
        network = mergeSection(network, base.network, Network::mergedOver),
    )

    /** Compact JSON bytes for the custom-data entry. */
    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        /** Custom-data key the policy travels under. */
        const val KEY = "dd:policy"

        /** Schema major version this build understands. */
        const val VERSION = 1

        /** No overrides at all. */
        val EMPTY = DisplayPolicy()

        /**
         * What a client uses for anything the server leaves unset. Sections without client-side handling yet
         * stay null, meaning "the client's own setting".
         */
        val CLIENT_DEFAULTS = DisplayPolicy(sync = SyncSettings.DEFAULT.toSection())

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = false
        }

        private val strictJson = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }

        private inline fun <T : Any> mergeSection(top: T?, base: T?, merge: (T, T?) -> T): T? =
            if (top == null) base else merge(top, base)

        /**
         * Reads a policy, or null when [bytes] is absent, not a JSON object, or from a newer schema major.
         * A section that does not parse (wrong types, garbage) is dropped on its own; the rest is kept.
         */
        fun decode(bytes: ByteArray?): DisplayPolicy? {
            if (bytes == null || bytes.isEmpty()) return null
            val root = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
                .getOrNull() ?: return null
            val version = (root["v"] as? JsonPrimitive)?.intOrNull ?: VERSION
            if (version > VERSION || version < 1) return null
            return DisplayPolicy(
                v = VERSION,
                sync = section(root, "sync", Sync.serializer()),
                audio = section(root, "audio", Audio.serializer()),
                visibility = section(root, "visibility", Visibility.serializer()),
                quality = section(root, "quality", Quality.serializer()),
                network = section(root, "network", Network.serializer()),
            )
        }

        private fun <T> section(root: JsonObject, name: String, serializer: KSerializer<T>): T? {
            val element = root[name] ?: return null
            if (element !is JsonObject) return null
            return runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()
        }

        /**
         * Returns [base] with the field at dotted [path] (`section.field`, e.g. `sync.deadBandMs`) set to
         * [value], or removed when [value] is null. Returns null when the path is unknown or the value has
         * the wrong type, so callers can reject bad input instead of storing it.
         */
        fun withField(base: DisplayPolicy?, path: String, value: Any?): DisplayPolicy? {
            val parts = path.split('.')
            if (parts.size != 2 || parts.any { it.isEmpty() }) return null
            val (sectionName, field) = parts
            val tree = json.encodeToJsonElement(serializer(), base ?: EMPTY).jsonObject.toMutableMap()
            val section = (tree[sectionName] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val element: JsonElement = when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                else -> return null
            }
            if (element == JsonNull) section.remove(field) else section[field] = element
            tree[sectionName] = JsonObject(section)
            val parsed = runCatching { strictJson.decodeFromJsonElement(serializer(), JsonObject(tree)) }
                .getOrNull() ?: return null
            return parsed.copy(v = VERSION)
        }
    }
}
