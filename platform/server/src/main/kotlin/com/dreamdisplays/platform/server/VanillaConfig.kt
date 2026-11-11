package com.dreamdisplays.platform.server

import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import com.dreamdisplays.core.config.ConfigLoads
import java.io.File
import java.nio.file.Files

/**
 * Vanilla (`Fabric` / `NeoForge`) server-side configuration. Parsing and language-loading logic live in
 * [ServerConfigModel]; this class only supplies the classloader resource lookup and data directory.
 *
 * Different from the `Paper` implementation ([Config]) mainly in resource lookup and locale API.
 */
class VanillaConfig(private val configDir: File) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val configFile = File(configDir, "config.toml")

    private val langStore = LanguageStore(
        dataDir = configDir,
        resourceLookup = { VanillaConfig::class.java.classLoader.getResourceAsStream(it) },
        warn = { logger.warn(it) },
    )

    lateinit var language: LanguageSection; private set
    lateinit var settings: SettingsSection; private set
    lateinit var storage: StorageSection; private set
    lateinit var permissions: PermissionsSection; private set
    lateinit var proxy: ProxySection; private set

    val messages get() = langStore.messages
    val languages get() = langStore.languages

    init {
        createDefaultConfig()
        langStore.extractLangFiles()
        load(firstLoad = true)
        langStore.loadMessages(logger)
    }

    private fun createDefaultConfig() {
        if (!configDir.exists()) configDir.mkdirs()
        if (!configFile.exists()) {
            val resource = VanillaConfig::class.java.classLoader
                .getResourceAsStream("assets/dreamdisplays/lang/server/config.toml")
            if (resource != null) {
                resource.use { Files.copy(it, configFile.toPath()) }
            } else {
                logger.error("Could not find bundled config.toml; writing an empty file.")
                configFile.createNewFile()
            }
        }
    }

    /** True when the file in effect was read at startup with errors: nothing destructive may run on its values. */
    @Volatile
    var loadedWithErrors = false; private set

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
    }

    fun reload() {
        load(firstLoad = false)
        langStore.extractLangFiles()
        langStore.loadMessages(logger)
    }

    fun getMessageForPlayer(player: ServerPlayer?, key: String): Any? {
        //? if >=1.20.2 {
        val locale = player?.clientInformation()?.language() ?: "en"
        //?} else
        /*val locale = player?.let(VanillaServerState.playerLanguage) ?: "en"*/
        return langStore.getMessage(locale, language.default_language, key)
    }
}

/** Registry name for the selection tool, e.g. `minecraft:diamond_axe`. */
val SettingsSection.selectionMaterialId: String get() = display.selection_material

/** Registry name for the display base, e.g. `minecraft:black_concrete`. */
val SettingsSection.baseMaterialId: String get() = display.base_material
