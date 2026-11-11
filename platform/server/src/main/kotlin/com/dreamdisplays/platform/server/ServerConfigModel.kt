package com.dreamdisplays.platform.server

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.shared.policy.CustomMediaPolicy
import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.toPlainJsonValue
import org.slf4j.Logger
import org.tomlj.TomlTable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Language section of the config. */
data class LanguageSection(
    val default_language: String = "en",
)

/** Settings section of the config. */
data class SettingsSection(
    val reports: ReportsConfig = ReportsConfig(),
    val updates: UpdatesConfig = UpdatesConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val fullscreen: FullscreenConfig = FullscreenConfig(),
    val customMedia: CustomMediaConfig = CustomMediaConfig(),
    /** Server-wide display policy defaults (`[policy.*]`); a display's own override is merged over these. */
    val policy: DisplayPolicy = DisplayPolicy.EMPTY,
    val clients: ClientsConfig = ClientsConfig(),
) {
    // Reports
    val webhookUrl get() = reports.webhook_url
    val reportCooldown get() = reports.cooldown * 1000L

    // Updates
    val repoName get() = updates.repo_name
    val repoOwner get() = updates.repo_owner
    val updatesEnabled get() = display.updates && updates.enabled

    // Particles
    val particlesEnabled get() = display.particles
    val particleRenderDelay = 2

    val particleColorRgb: Int
        get() = display.particles_color.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0x00FFFF

    // Mod detection
    val modDetectionEnabled get() = display.mod_detection_enabled

    // Display
    val minWidth get() = display.min_width
    val minHeight get() = display.min_height
    val maxWidth get() = display.max_width
    val maxHeight get() = display.max_height

    /** Max displays one player may own at once; `0` or less means no limit. Bypassed by [PermissionsSection.createBypass]. */
    val maxDisplaysPerPlayer get() = display.max_displays_per_player

    /** How long a resumed synced display stays parked so every viewer can snap onto it (0 = no hold). */
    val resumeHoldMs get() = display.resume_hold_seconds * 1000L

    /** How long a seeked / restarted synced display stays parked while viewers re-buffer (0 = no hold). */
    val seekHoldMs get() = display.seek_hold_seconds * 1000L
    val startLeadMaxMs get() = display.start_lead_max_ms.toLong()

    /** Mode new displays are created in: [PlaybackMode.SYNCED] unless `default_mode = "local"`. */
    val defaultMode: PlaybackMode
        get() = if (display.default_mode.trim().equals("local", ignoreCase = true)) PlaybackMode.LOCAL
        else PlaybackMode.SYNCED

    /** A playing synced display nobody is near is paused after this long (0 = never). */
    val autoPauseWhenEmptyMs get() = display.auto_pause_when_empty_seconds * 1000L

    /** Volume in [0, 1] ready for the wire. */
    val defaultVolume get() = display.default_volume / 200f

    // Custom media (player-pasted links)
    val customMediaPolicy: CustomMediaPolicy.Settings
        get() = CustomMediaPolicy.Settings(
            enabled = customMedia.enabled,
            allowedHosts = customMedia.allowed_hosts,
            blockedHosts = customMedia.blocked_hosts,
        )

    // Fullscreen broadcasts
    val fullscreenAllowForced get() = fullscreen.allow_forced
    val fullscreenQualityCap get() = fullscreen.quality_cap
    val fullscreenPrepareTimeoutMs get() = fullscreen.prepare_timeout_seconds * 1000L
    val fullscreenDefaultMode: FullscreenMode
        get() = runCatching { FullscreenMode.valueOf(fullscreen.default_mode.uppercase()) }
            .getOrDefault(FullscreenMode.STANDARD)

    /** Reports section. */
    data class ReportsConfig(
        val webhook_url: String = "",
        val cooldown: Int = 15,
    )

    /** Updates section. */
    data class UpdatesConfig(
        val enabled: Boolean = true,
        val repo_name: String = "dreamdisplays",
        val repo_owner: String = "arnodoelinger",
    )

    /** Display section. Materials are registry ids (e.g. `minecraft:diamond_axe`); platform code
     *  resolves them to their own material type (see `selectionMaterial`/`baseMaterial` in [Config]/[VanillaConfig]). */
    data class DisplayConfig(
        val selection_material: String = "minecraft:diamond_axe",
        val base_material: String = "minecraft:black_concrete",
        val updates: Boolean = true,
        val particles: Boolean = true,
        val particles_color: String = "#00FFFF",
        val mod_detection_enabled: Boolean = true,
        val min_width: Int = 1,
        val min_height: Int = 1,
        val max_width: Int = 32,
        val max_height: Int = 24,
        val default_volume: Int = 50,
        val max_displays_per_player: Int = 100,
        val resume_hold_seconds: Int = 1,
        val seek_hold_seconds: Int = 3,
        val start_lead_max_ms: Int = 150,
        val default_mode: String = "synced",
        val auto_pause_when_empty_seconds: Int = 0,
    )

    /**
     * Custom-media section: the policy for links players paste themselves, as opposed to the
     * platform pages the mod resolves. Off-by-host rather than off-by-default, so the feature works
     * out of the box while still giving an operator a switch and an allow / block list.
     */
    data class CustomMediaConfig(
        val enabled: Boolean = true,
        val allowed_hosts: List<String> = [],
        val blocked_hosts: List<String> = [],
    )

    /** Clients section: [latest_build] is the build id `/display clients` compares players against (empty = none). */
    data class ClientsConfig(
        val latest_build: String = "",
    )

    /** Fullscreen broadcast section. */
    data class FullscreenConfig(
        val default_mode: String = "STANDARD",
        val allow_forced: Boolean = true,
        val quality_cap: Int = 1080,
        val prepare_timeout_seconds: Int = 600,
    )
}

/** Storage section of the config. */
data class StorageSection(
    val storage: StorageConfig = StorageConfig(),
) {
    val type get() = storage.type
    val host get() = storage.host
    val port get() = storage.port
    val database get() = storage.database
    val password get() = storage.password
    val username get() = storage.username
    val tablePrefix get() = storage.table_prefix
    val useSSL get() = storage.use_ssl

    data class StorageConfig(
        val type: String = StorageBackend.SQLITE.configToken,
        val host: String = "localhost",
        val port: String = "3306",
        val database: String = "my_database",
        val password: String = "veryStrongPassword",
        val username: String = "username",
        val table_prefix: String = "",
        val use_ssl: Boolean = false,
    )
}

/** Permissions section of the config. */
data class PermissionsSection(
    val permissions: PermissionsConfig = PermissionsConfig(),
) {
    val create get() = permissions.create
    val video get() = permissions.video
    val info get() = permissions.info
    val premium get() = permissions.premium
    val delete get() = permissions.delete
    val list get() = permissions.list
    val reload get() = permissions.reload
    val updates get() = permissions.updates
    val help get() = permissions.help
    val stats get() = permissions.stats
    val toggleOthers get() = permissions.toggle_others
    val local get() = permissions.local
    val synced get() = permissions.synced
    val broadcast get() = permissions.broadcast
    val watchparty get() = permissions.watchparty
    val lock get() = permissions.lock
    val deleteOthers get() = permissions.delete_others
    val createBypass get() = permissions.create_bypass
    val fullscreenStart get() = permissions.fullscreen_start
    val fullscreenStop get() = permissions.fullscreen_stop
    val fullscreenList get() = permissions.fullscreen_list
    val fullscreenNetwork get() = permissions.fullscreen_network
    val remote get() = permissions.remote
    val custom get() = permissions.custom
    val name get() = permissions.name
    val nameOthers get() = permissions.name_others
    val schedule get() = permissions.schedule
    val scheduleOthers get() = permissions.schedule_others

    data class PermissionsConfig(
        val create: String = "dreamdisplays.create",
        val video: String = "dreamdisplays.video",
        val info: String = "dreamdisplays.info",
        val premium: String = "group.premium",
        val delete: String = "dreamdisplays.delete",
        val list: String = "dreamdisplays.list",
        val reload: String = "dreamdisplays.reload",
        val updates: String = "dreamdisplays.updates",
        val help: String = "dreamdisplays.help",
        val stats: String = "dreamdisplays.stats",
        val toggle_others: String = "dreamdisplays.toggle.others",
        val local: String = "dreamdisplays.local",
        val synced: String = "dreamdisplays.synced",
        val broadcast: String = "dreamdisplays.broadcast",
        val watchparty: String = "dreamdisplays.watchparty",
        val lock: String = "dreamdisplays.lock",
        val delete_others: String = "dreamdisplays.delete.others",
        val create_bypass: String = "dreamdisplays.create.bypass",
        val fullscreen_start: String = "dreamdisplays.fullscreen.start",
        val fullscreen_stop: String = "dreamdisplays.fullscreen.stop",
        val fullscreen_list: String = "dreamdisplays.fullscreen.list",
        val fullscreen_network: String = "dreamdisplays.fullscreen.network",
        val remote: String = "dreamdisplays.remote",
        val custom: String = "dreamdisplays.custom",
        val name: String = "dreamdisplays.name",
        val name_others: String = "dreamdisplays.name.others",
        val schedule: String = "dreamdisplays.schedule",
        val schedule_others: String = "dreamdisplays.schedule.others",
    )
}

/**
 * `[proxy]` section: opt-in `BungeeCord` / `Velocity` network coordination. Deliberately has no
 * `server_name` key — the backend learns its own name from the proxy's `ProxyWelcome` instead of
 * being told it twice in two configs. See `ProxyBridge` / `ProxyClock`.
 */
data class ProxySection(
    val enabled: Boolean = false,
    val clock_sync_interval: Int = 5,
)

/** Parsed result of a `config.toml`. */
data class ParsedServerConfig(
    val language: LanguageSection,
    val settings: SettingsSection,
    val storage: StorageSection,
    val permissions: PermissionsSection,
    val proxy: ProxySection,
)

/** Parses [t] into a [ParsedServerConfig], falling back to defaults for missing or malformed sections. */
fun parseServerConfig(t: TomlTable?): ParsedServerConfig = ParsedServerConfig(
    language = LanguageSection(
        default_language = t?.getString("language.default_language") ?: "en"
    ),
    settings = SettingsSection(
        reports = SettingsSection.ReportsConfig(
            webhook_url = t?.getString("reports.webhook_url") ?: "",
            cooldown = t?.getLong("reports.cooldown")?.toInt() ?: 15
        ),
        updates = SettingsSection.UpdatesConfig(
            enabled = t?.getBoolean("updates.enabled") ?: true,
            repo_name = t?.getString("updates.repo_name") ?: "dreamdisplays",
            repo_owner = t?.getString("updates.repo_owner") ?: "arnodoelinger"
        ),
        display = SettingsSection.DisplayConfig(
            selection_material = t?.getString("display.selection_material") ?: "minecraft:diamond_axe",
            base_material = t?.getString("display.base_material") ?: "minecraft:black_concrete",
            updates = t?.getBoolean("display.updates") ?: true,
            particles = t?.getBoolean("display.particles") ?: true,
            particles_color = t?.getString("display.particles_color") ?: "#00FFFF",
            mod_detection_enabled = t?.getBoolean("display.mod_detection_enabled") ?: true,
            min_width = t?.getLong("display.min_width")?.toInt() ?: 1,
            min_height = t?.getLong("display.min_height")?.toInt() ?: 1,
            max_width = t?.getLong("display.max_width")?.toInt() ?: 32,
            max_height = t?.getLong("display.max_height")?.toInt() ?: 24,
            default_volume = t.intOr("display.default_volume", 50).coerceIn(0, 200),
            max_displays_per_player = t?.getLong("display.max_displays_per_player")?.toInt() ?: 100,
            resume_hold_seconds = t.numberOrNull("display.resume_hold_seconds")?.toInt()?.coerceIn(0, 60) ?: 1,
            seek_hold_seconds = t.numberOrNull("display.seek_hold_seconds")?.toInt()?.coerceIn(0, 60) ?: 3,
            start_lead_max_ms = t.numberOrNull("display.start_lead_max_ms")?.toInt()?.coerceIn(0, 1_000) ?: 150,
            default_mode = (t?.get("display.default_mode") as? String) ?: "synced",
            auto_pause_when_empty_seconds = t.numberOrNull("display.auto_pause_when_empty_seconds")?.toInt()
                ?.coerceIn(0, 86_400) ?: 0,
        ),
        customMedia = SettingsSection.CustomMediaConfig(
            enabled = t?.getBoolean("custom_media.enabled") ?: true,
            allowed_hosts = stringList(t, "custom_media.allowed_hosts"),
            blocked_hosts = stringList(t, "custom_media.blocked_hosts"),
        ),
        policy = parsePolicy(t),
        fullscreen = SettingsSection.FullscreenConfig(
            default_mode = t?.getString("fullscreen.default_mode") ?: "STANDARD",
            allow_forced = t?.getBoolean("fullscreen.allow_forced") ?: true,
            quality_cap = t?.getLong("fullscreen.quality_cap")?.toInt() ?: 1080,
            prepare_timeout_seconds = t?.getLong("fullscreen.prepare_timeout_seconds")?.toInt()?.coerceIn(10, 86_400) ?: 600,
        ),
        clients = SettingsSection.ClientsConfig(
            latest_build = (t?.get("clients.latest_build") as? String)?.trim() ?: "",
        ),
    ),
    storage = StorageSection(
        storage = StorageSection.StorageConfig(
            type = t?.getString("storage.type") ?: StorageBackend.SQLITE.configToken,
            host = t?.getString("storage.host") ?: "localhost",
            port = t?.getString("storage.port") ?: "3306",
            database = t?.getString("storage.database") ?: "my_database",
            password = t?.getString("storage.password") ?: "veryStrongPassword",
            username = t?.getString("storage.username") ?: "username",
            table_prefix = t?.getString("storage.table_prefix") ?: "",
            use_ssl = t?.getBoolean("storage.use_ssl") ?: false,
        )
    ),
    permissions = PermissionsSection(
        permissions = PermissionsSection.PermissionsConfig(
            create = t?.getString("permissions.create") ?: "dreamdisplays.create",
            video = t?.getString("permissions.video") ?: "dreamdisplays.video",
            info = t?.getString("permissions.info") ?: "dreamdisplays.info",
            premium = t?.getString("permissions.premium") ?: "group.premium",
            delete = t?.getString("permissions.delete") ?: "dreamdisplays.delete",
            list = t?.getString("permissions.list") ?: "dreamdisplays.list",
            reload = t?.getString("permissions.reload") ?: "dreamdisplays.reload",
            updates = t?.getString("permissions.updates") ?: "dreamdisplays.updates",
            help = t?.getString("permissions.help") ?: "dreamdisplays.help",
            stats = t?.getString("permissions.stats") ?: "dreamdisplays.stats",
            toggle_others = t?.getString("permissions.toggle_others") ?: "dreamdisplays.toggle.others",
            local = t?.getString("permissions.local") ?: "dreamdisplays.local",
            synced = t?.getString("permissions.synced") ?: "dreamdisplays.synced",
            broadcast = t?.getString("permissions.broadcast") ?: "dreamdisplays.broadcast",
            watchparty = t?.getString("permissions.watchparty") ?: "dreamdisplays.watchparty",
            lock = t?.getString("permissions.lock") ?: "dreamdisplays.lock",
            delete_others = t?.getString("permissions.delete_others") ?: "dreamdisplays.delete.others",
            create_bypass = t?.getString("permissions.create_bypass") ?: "dreamdisplays.create.bypass",
            fullscreen_start = t?.getString("permissions.fullscreen_start") ?: "dreamdisplays.fullscreen.start",
            fullscreen_stop = t?.getString("permissions.fullscreen_stop") ?: "dreamdisplays.fullscreen.stop",
            fullscreen_list = t?.getString("permissions.fullscreen_list") ?: "dreamdisplays.fullscreen.list",
            fullscreen_network = t?.getString("permissions.fullscreen_network") ?: "dreamdisplays.fullscreen.network",
            remote = t?.getString("permissions.remote") ?: "dreamdisplays.remote",
            custom = t?.getString("permissions.custom") ?: "dreamdisplays.custom",
            name = t?.getString("permissions.name") ?: "dreamdisplays.name",
            name_others = t?.getString("permissions.name_others") ?: "dreamdisplays.name.others",
            schedule = t?.getString("permissions.schedule") ?: "dreamdisplays.schedule",
            schedule_others = t?.getString("permissions.schedule_others") ?: "dreamdisplays.schedule.others",
        )
    ),
    proxy = ProxySection(
        enabled = t?.getBoolean("proxy.enabled") ?: false,
        clock_sync_interval = t?.getLong("proxy.clock_sync_interval")?.toInt() ?: 5,
    ),
)

private fun TomlTable?.intOr(key: String, default: Int): Int {
    val table = this ?: return default
    table.getLong(key)?.toInt()?.let { return it }
    table.getDouble(key)?.toInt()?.let { return it }
    return default
}

private fun TomlTable?.numberOrNull(key: String): Number? = this?.get(key) as? Number

private fun TomlTable?.boolOrNull(key: String): Boolean? = this?.get(key) as? Boolean

/**
 * Reads the `[policy.*]` tables. Absent keys stay null (= client default); a wrongly typed value is
 * ignored the same way rather than failing the whole config.
 */
internal fun parsePolicy(t: TomlTable?): DisplayPolicy {
    val sync = DisplayPolicy.Sync(
        enabled = t.boolOrNull("policy.sync.enabled"),
        deadBandMs = t.numberOrNull("policy.sync.dead_band_ms")?.toInt(),
        maxRatePct = t.numberOrNull("policy.sync.max_rate_pct")?.toDouble(),
        seekThresholdMs = t.numberOrNull("policy.sync.seek_threshold_ms")?.toInt(),
        seekCooldownMs = t.numberOrNull("policy.sync.seek_cooldown_ms")?.toInt(),
    )
    val audio = DisplayPolicy.Audio(
        radiusBlocks = t.numberOrNull("policy.audio.radius_blocks")?.toDouble(),
        fullVolumeBlocks = t.numberOrNull("policy.audio.full_volume_blocks")?.toDouble(),
        falloff = (t?.get("policy.audio.falloff") as? String)?.trim()?.uppercase()
            ?.let { name -> DisplayPolicy.Falloff.entries.firstOrNull { it.name == name } },
        startVolume = t.numberOrNull("policy.audio.start_volume")?.toDouble(),
        maxVolume = t.numberOrNull("policy.audio.max_volume")?.toDouble(),
    )
    val visibility = DisplayPolicy.Visibility(t.numberOrNull("policy.visibility.max_distance_blocks")?.toDouble())
    val quality = DisplayPolicy.Quality(t.numberOrNull("policy.quality.max_height")?.toInt())
    val client = DisplayPolicy.Client(
        enabled = t.boolOrNull("policy.client.enabled"),
        maxActiveDisplays = t.numberOrNull("policy.client.max_active_displays")?.toInt(),
        hardwareDecoding = t.boolOrNull("policy.client.hardware_decoding"),
        audioAcoustics = (t?.get("policy.client.audio_acoustics") as? String)?.trim()?.uppercase()
            ?.let { name -> DisplayPolicy.Acoustics.entries.firstOrNull { it.name == name } },
        unshadedDisplays = t.boolOrNull("policy.client.unshaded_displays"),
        displayDistanceChunks = t.numberOrNull("policy.client.display_distance_chunks")?.toInt(),
        audioOutputProfile = (t?.get("policy.client.audio_output_profile") as? String)?.trim()?.uppercase()
            ?.let { name -> DisplayPolicy.OutputProfile.entries.firstOrNull { it.name == name } },
        audioNormalization = t.boolOrNull("policy.client.audio_normalization"),
    )
    val debug = DisplayPolicy.Debug(
        syncLogIntervalSeconds = t.numberOrNull("policy.debug.sync_log_interval_seconds")?.toInt(),
        verbose = t.boolOrNull("policy.debug.verbose"),
        reportIntervalSeconds = t.numberOrNull("policy.debug.report_interval_seconds")?.toInt(),
    )
    val network = DisplayPolicy.Network(t.numberOrNull("policy.network.read_ahead_seconds")?.toInt())
    return DisplayPolicy(
        sync = sync.takeIf { it != DisplayPolicy.Sync() },
        audio = audio.takeIf { it != DisplayPolicy.Audio() },
        visibility = visibility.takeIf { it != DisplayPolicy.Visibility() },
        quality = quality.takeIf { it != DisplayPolicy.Quality() },
        client = client.takeIf { it != DisplayPolicy.Client() },
        debug = debug.takeIf { it != DisplayPolicy.Debug() },
        network = network.takeIf { it != DisplayPolicy.Network() },
    )
}

private fun stringList(t: TomlTable?, key: String): List<String> =
    t?.getArrayOrEmpty(key)
        ?.toList()
        ?.filterIsInstance<String>()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: []

/** Language files bundled with every server platform's JAR. */
internal val LANGUAGE_FILES = [
    "en.json", "es.json", "fr.json", "it.json", "pl.json",
    "ru.json", "uk.json", "de.json", "cs.json", "be.json", "he.json",
    "zh.json",
]

/** Maps a Minecraft locale string (e.g. `ru_ru`, `ru-ru`) to the plugin's short language code (e.g. `ru`). */
internal fun mapLocaleToLang(locale: String): String {
    return when (val normalized = locale.lowercase()) {
        "ru_ru", "ru-ru" -> "ru"
        "uk_ua", "uk-ua" -> "uk"
        "pl_pl", "pl-pl" -> "pl"
        "de_de", "de-de" -> "de"
        "cs_cz", "cs-cz" -> "cs"
        "be_by", "be-by" -> "be"
        "he_il", "he-il" -> "he"
        "zh_cn", "zh-cn" -> "zh"
        else -> normalized.substringBefore('_').substringBefore('-').ifEmpty { "en" }
    }
}

private fun loadLanguageMessages(file: File): Map<String, Any> = parseLanguageMessages(file.readText())

private fun parseLanguageMessages(text: String): Map<String, Any> {
    val root = DreamJson.compact.parseToJsonElement(text).asJsonObjectOrNull()
        ?: error("Language file root must be a JSON object.")
    return root.mapNotNull { (key, value) ->
        value.toPlainJsonValue()?.let { key to it }
    }.toMap()
}

/** Extracts bundled per-language JSONs into [dataDir] / lang and loads them as message lookups. */
internal class LanguageStore(
    private val dataDir: File,
    private val resourceLookup: (String) -> InputStream?,
    private val warn: (String) -> Unit,
) {
    val messages = mutableMapOf<String, Any>()
    val languages = mutableMapOf<String, Map<String, Any>>()

    fun extractLangFiles() {
        val langFolder = File(dataDir, "lang")
        if (!langFolder.exists() && !langFolder.mkdirs()) {
            warn("Could not create lang folder")
            return
        }

        LANGUAGE_FILES.forEach { fileName ->
            runCatching {
                val target = File(langFolder, fileName)
                if (target.exists() && isValidLangFile(target)) return@forEach

                val resource = resourceLookup("assets/dreamdisplays/lang/server/$fileName")
                    ?: resourceLookup("assets/dreamdisplays/lang/$fileName")
                resource?.use { input ->
                    if (target.exists()) {
                        warn("Language file $fileName is corrupt; restoring the bundled default.")
                    }
                    Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure {
                warn("Could not extract $fileName: ${it.message}")
            }
        }
    }

    private fun bundledMessages(fileName: String): Map<String, Any> = runCatching {
        val resource = resourceLookup("assets/dreamdisplays/lang/server/$fileName")
            ?: resourceLookup("assets/dreamdisplays/lang/$fileName")
        resource?.use { parseLanguageMessages(it.readBytes().decodeToString()) }
    }.getOrNull() ?: emptyMap()

    private fun isValidLangFile(file: File): Boolean =
        runCatching { loadLanguageMessages(file) }.isSuccess

    fun loadMessages(logger: Logger) {
        languages.clear()
        LANGUAGE_FILES.forEach { fileName ->
            val langCode = fileName.removeSuffix(".json")
            val langFile = File(dataDir, "lang/$fileName")
            val bundled = bundledMessages(fileName)
            if (langFile.exists()) {
                runCatching {
                    languages[langCode] = bundled + loadLanguageMessages(langFile)
                }.onFailure {
                    logger.error("Error loading language file: $fileName.", it)
                }
            } else if (bundled.isNotEmpty()) {
                languages[langCode] = bundled
            }
        }
        messages.clear()
        messages.putAll(languages["en"] ?: emptyMap())
    }

    /** Resolves [key] in [locale], then in [defaultLocale], then in the English fallback. */
    fun getMessage(locale: String, defaultLocale: String, key: String): Any? {
        val langCode = mapLocaleToLang(locale)
        val defaultLangCode = mapLocaleToLang(defaultLocale)
        return languages[langCode]?.get(key)
            ?: languages[defaultLangCode]?.get(key)
            ?: messages[key]
    }
}
