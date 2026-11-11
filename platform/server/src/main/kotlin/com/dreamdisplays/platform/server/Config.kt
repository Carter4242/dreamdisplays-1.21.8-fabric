package com.dreamdisplays.platform.server

import com.dreamdisplays.core.config.ConfigLoads
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import java.io.File
import java.nio.file.Files

/**
 * Manages the configuration of the plugin. Parsing and language-loading logic live in
 * [ServerConfigModel]; this class only supplies Paper's resource lookup, data directory,
 * and `Material` resolution.
 */
@PaperOnly
@NullMarked
class Config(private val plugin: PaperServer) {
    private val configFile = File(plugin.dataFolder, "config.toml")

    /** The `config.toml` this config is read from. */
    val file: File get() = configFile
    private val logger = LoggerFactory.getLogger(javaClass)

    private val langStore = LanguageStore(
        dataDir = plugin.dataFolder,
        resourceLookup = { plugin.getResource(it) },
        warn = { plugin.logger.warning(it) },
    )

    /** Config language. */
    lateinit var language: LanguageSection; private set

    /** Config settings. */
    lateinit var settings: SettingsSection; private set

    /** Config storage type. */
    lateinit var storage: StorageSection; private set

    /** Config permissions. */
    lateinit var permissions: PermissionsSection; private set

    /** Config proxy (`BungeeCord` / `Velocity` network coordination) section. */
    lateinit var proxy: ProxySection; private set

    /** Config messages. */
    val messages get() = langStore.messages

    /** Config languages. */
    val languages get() = langStore.languages

    /**
     * True when the `config.toml` in effect was read with syntax errors at startup: keys on broken lines run on their
     * defaults, so nothing destructive may act on it (the startup display check). Cleared by a clean reload.
     */
    @Volatile
    var loadedWithErrors = false; private set

    /** Runs after every successful [reload] (the platform re-sends what clients learned from the old settings). */
    var afterReload: () -> Unit = {}

    /** Initializes the plugin's configuration. */
    init {
        createDefaultConfig()
        langStore.extractLangFiles()
        load(firstLoad = true)
        langStore.loadMessages(logger)
    }

    private fun createDefaultConfig() {
        if (!configFile.exists()) {
            plugin.dataFolder.mkdirs()
            plugin.getResource("config.toml")?.use {
                Files.copy(it, configFile.toPath())
            } ?: plugin.logger.severe("Could not create default config.toml")
        }
    }

    private fun load(firstLoad: Boolean) {
        val result: TomlParseResult? = runCatching { Toml.parse(configFile.toPath()) }
            .onFailure { logger.error("Failed to read config.toml.", it) }
            .getOrNull()
        val errors = result?.errors().orEmpty()
        errors.forEach { logger.error("config.toml: {}", it.toString()) }
        when (ConfigLoads.decide(hasErrors = result == null || errors.isNotEmpty(), firstLoad = firstLoad)) {
            ConfigLoads.Action.APPLY -> loadedWithErrors = false
            ConfigLoads.Action.APPLY_DEGRADED -> {
                loadedWithErrors = true
                logger.error("config.toml has errors: the keys they hit run on their defaults until it is fixed and reloaded.")
            }
            ConfigLoads.Action.KEEP_PREVIOUS ->
                throw IllegalStateException("config.toml has ${errors.size.coerceAtLeast(1)} error(s); the previous settings stay")
        }
        val parsed = parseServerConfig(result)
        language = parsed.language
        settings = parsed.settings
        storage = parsed.storage
        permissions = parsed.permissions
        proxy = parsed.proxy
        settings.live.relayProblem?.let { logger.warn("config.toml [live]: {}; screen sharing is off.", it) }
    }

    /**
     * Re-reads `config.toml`, re-extracts language files and refreshes the in-memory message map. A file with syntax
     * errors changes nothing and throws (the reload is reported as failed).
     */
    fun reload() {
        load(firstLoad = false)
        langStore.extractLangFiles()
        langStore.loadMessages(logger)
        runCatching { afterReload() }.onFailure { logger.warn("Could not apply the reloaded config everywhere.", it) }
    }

    /**
     * Resolves [key] in [player]'s locale, then in the configured default, then in the English fallback.
     * Returns null when no translation exists in any language.
     */
    fun getMessageForPlayer(player: Player?, key: String): Any? =
        langStore.getMessage(player?.locale()?.toString() ?: "en_us", language.default_language, key)
}
