package com.dreamdisplays.platform.server.policy

import com.dreamdisplays.core.display.policy.ClientLogLevel
import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.platform.server.PaperServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player client log levels set with `/display debug <player> <level>`, kept in `client-log-levels.properties`
 * (uuid = level) in the plugin folder. A player without one gets the server-wide `[policy.debug] log_level`. The level
 * reaches the client inside its [com.dreamdisplays.core.protocol.common.packets.ServerPolicy] (re-sent within a
 * second of a change), so it applies live.
 */
@PaperOnly
object ClientLogLevels {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val FILE = "client-log-levels.properties"

    private val levels = ConcurrentHashMap<UUID, ClientLogLevel>()

    @Volatile
    private var loaded = false

    private fun file(): File = File(PaperServer.getInstance().dataFolder, FILE)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            runCatching {
                val f = file()
                if (!f.isFile) return@runCatching
                val props = Properties().apply { f.inputStream().use(::load) }
                props.forEach { (k, v) ->
                    val id = runCatching { UUID.fromString(k.toString()) }.getOrNull() ?: return@forEach
                    ClientLogLevel.fromWire(v.toString())?.let { levels[id] = it }
                }
            }.onFailure { logger.warn("Could not read {}: {}", FILE, it.message) }
        }
    }

    /** The level set for [playerId], or null when it follows the server-wide default. */
    fun get(playerId: UUID): ClientLogLevel? {
        ensureLoaded()
        return levels[playerId]
    }

    /** Sets (null = clears) [playerId]'s level and saves the file. */
    fun set(playerId: UUID, level: ClientLogLevel?) {
        ensureLoaded()
        if (level == null) levels.remove(playerId) else levels[playerId] = level
        save()
    }

    /** [base] (the server-wide client policy) with [playerId]'s own level on top, if it has one. */
    fun applyTo(base: DisplayPolicy, playerId: UUID): DisplayPolicy {
        val level = get(playerId) ?: return base
        val debug = (base.debug ?: DisplayPolicy.Debug()).copy(logLevel = level.wire)
        return base.copy(debug = debug)
    }

    private fun save() = synchronized(this) {
        runCatching {
            val props = Properties()
            levels.forEach { (id, level) -> props[id.toString()] = level.wire }
            val f = file()
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.outputStream().use { props.store(it, "Dream Displays per-player client log levels (/display debug)") }
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { logger.warn("Could not save {}: {}", FILE, it.message) }
    }
}
