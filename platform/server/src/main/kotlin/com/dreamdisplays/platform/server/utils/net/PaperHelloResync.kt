package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.HelloResync
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.utils.LuckPermsRecalc
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Sends an online client a fresh hello when what it may do changed (see [HelloResync]): rechecked shortly after
 * `LuckPerms` recalculates the player's permissions, and for everyone every few seconds.
 *
 * A revoked share permission also ends a share that runs: [ScreenShareActions.tick] ends it within a second, telling
 * the sharer "You may no longer share your screen here.", and the hello takes the share button away.
 */
@PaperOnly
@NullMarked
object PaperHelloResync {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val resync = HelloResync<ServerHello>()

    @Volatile
    private var subscription: AutoCloseable? = null

    /** Plugin enable: watches `LuckPerms` when it runs. */
    fun start(plugin: PaperServer) {
        stop()
        if (!plugin.server.pluginManager.isPluginEnabled("LuckPerms")) return
        subscription = runCatching {
            LuckPermsRecalc.subscribe(plugin) { id -> resync.request(id, System.currentTimeMillis()) }
        }.onFailure {
            logger.warn("Could not watch LuckPerms for permission changes; online players' display permissions update every few seconds instead.", it)
        }.getOrNull()
    }

    /** Plugin disable: stops watching `LuckPerms` and forgets everyone. */
    fun stop() {
        subscription?.let { runCatching { it.close() } }
        subscription = null
        resync.clear()
    }

    /** [player] was sent [hello] (their handshake, a config reload). */
    fun onSent(player: Player, hello: ServerHello) {
        resync.onSent(player.uniqueId, hello.copy(greeting = false))
    }

    /** [playerId] left. */
    fun onQuit(playerId: UUID) {
        resync.forget(playerId)
    }

    /** Main / global thread, every couple of ticks: sends the hellos that changed. */
    fun tick() {
        val now = System.currentTimeMillis()
        val ids = resync.takeDue(now).toMutableSet()
        if (resync.sweepDue(now)) ids += resync.known()
        if (ids.isEmpty()) return
        val updated = ArrayList<String>()
        for (id in ids) {
            val player = Bukkit.getPlayer(id) ?: continue
            if (!V2PlayerTracker.isV2(id)) continue
            val hello = PaperV2Networking.buildServerHello(player)
            if (!resync.changed(id, hello)) continue
            PaperV2Networking.send([player], hello)
            updated += player.name
        }
        if (updated.isNotEmpty()) {
            logger.info("What {} may do with displays changed; sent them a fresh hello: {}.", plural(updated.size), updated.sorted().joinToString())
        }
    }

    private fun plural(n: Int) = if (n == 1) "1 player" else "$n players"
}
