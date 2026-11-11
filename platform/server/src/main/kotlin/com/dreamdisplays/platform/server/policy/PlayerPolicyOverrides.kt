package com.dreamdisplays.platform.server.policy

import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.display.policy.PlayerPolicies
import com.dreamdisplays.platform.server.PaperServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player client policy overrides set with `/display policy player <player> <key> <value>` ([PlayerPolicies]),
 * kept in `player-policies.properties` (uuid = policy JSON) in the plugin folder. They reach the player's client inside
 * its [com.dreamdisplays.core.protocol.common.packets.ServerPolicy] within a second of a change ([ClientDiagnostics]).
 */
@PaperOnly
object PlayerPolicyOverrides {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val FILE = "player-policies.properties"

    private val overrides = ConcurrentHashMap<UUID, DisplayPolicy>()

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
                    DisplayPolicy.decode(v.toString().toByteArray(Charsets.UTF_8))?.serverWide()
                        ?.takeIf { !it.isEmpty }?.let { overrides[id] = it }
                }
            }.onFailure { logger.warn("Could not read {}: {}", FILE, it.message) }
        }
    }

    /** [playerId]'s own override, or null when they get the server-wide policy unchanged. */
    fun get(playerId: UUID): DisplayPolicy? {
        ensureLoaded()
        return overrides[playerId]
    }

    /** Replaces [playerId]'s override ([DisplayPolicy.EMPTY] or null removes it) and saves the file. */
    fun set(playerId: UUID, override: DisplayPolicy?) {
        ensureLoaded()
        if (override == null || override.isEmpty) overrides.remove(playerId) else overrides[playerId] = override
        save()
    }

    /** [base] (the server-wide client policy) with [playerId]'s override on top, if they have one. */
    fun applyTo(base: DisplayPolicy, playerId: UUID): DisplayPolicy = PlayerPolicies.applyTo(base, get(playerId))

    private fun save() = synchronized(this) {
        runCatching {
            val props = Properties()
            overrides.forEach { (id, policy) -> props[id.toString()] = policy.encode().toString(Charsets.UTF_8) }
            val f = file()
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.outputStream().use { props.store(it, "Dream Displays per-player client policy (/display policy player)") }
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { logger.warn("Could not save {}: {}", FILE, it.message) }
    }
}
