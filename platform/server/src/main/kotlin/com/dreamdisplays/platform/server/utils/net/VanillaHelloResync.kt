package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.HelloResync
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.utils.LuckPermsRecalc
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.*

/**
 * `Fabric` / `NeoForge` / `Forge`: sends an online client a fresh hello when what it may do changed (see
 * [HelloResync]): rechecked shortly after `LuckPerms` recalculates the player's permissions, and for everyone every few
 * seconds (op status, which decides without `LuckPerms`, announces nothing).
 */
@ModLoaderOnly
object VanillaHelloResync {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val resync = HelloResync<ServerHello>()

    @Volatile
    private var subscription: AutoCloseable? = null

    /** Server start: watches `LuckPerms` when it runs. */
    fun start() {
        stop()
        subscription = runCatching {
            LuckPermsRecalc.subscribe(null) { id -> resync.request(id, System.currentTimeMillis()) }
        }.onFailure {
            logger.warn("Could not watch LuckPerms for permission changes; online players' display permissions update every few seconds instead.", it)
        }.getOrNull()
    }

    /** Server stop (single-player: the next world starts another server in this process): unsubscribes, forgets all. */
    fun stop() {
        subscription?.let { runCatching { it.close() } }
        subscription = null
        resync.clear()
    }

    /** Sends [player] their hello as it stands now, remembered so a later change is sent too. */
    fun sendHello(player: ServerPlayer) {
        val hello = VanillaDisplayActions.buildServerHello(player)
        VanillaNetworking.adapter.sendV2([player], hello)
        resync.onSent(player.uuid, hello)
    }

    /** [playerId] left. */
    fun onLeave(playerId: UUID) {
        resync.forget(playerId)
    }

    /** Server thread, every tenth of a second: sends the hellos that changed. */
    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val ids = resync.takeDue(now).toMutableSet()
        if (resync.sweepDue(now)) ids += resync.known()
        if (ids.isEmpty()) return
        val updated = ArrayList<String>()
        for (id in ids) {
            val player = server.playerList.getPlayer(id) ?: continue
            if (!V2PlayerTracker.isV2(id)) continue
            val hello = VanillaDisplayActions.buildServerHello(player)
            if (!resync.changed(id, hello)) continue
            VanillaNetworking.adapter.sendV2([player], hello)
            updated += player.name.string
        }
        if (updated.isNotEmpty()) {
            val who = if (updated.size == 1) "1 player" else "${updated.size} players"
            logger.info("What {} may do with displays changed; sent them a fresh hello: {}.", who, updated.sorted().joinToString())
        }
    }
}
