package com.dreamdisplays.platform.server.listeners

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PaperServer.Companion.config
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.managers.SelectionManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.PipPinManager
import com.dreamdisplays.platform.server.playback.ReadinessTracker
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.platform.server.utils.net.VanillaServerScheduler
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
//? if >=1.21.1 {
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
//?}
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Handles player join and leave events. If mod detection is enabled, schedules a delayed `modRequired` message for
 * vanilla clients.
 */
@PaperOnly
@NullMarked
class PlayerListener : Listener {
    private var hasValidatedWorld = false

    /** After a world change: the client has switched level and is holding its carried PiPs for their re-announce. */
    private val PIN_REANNOUNCE_DELAY_TICKS = 40L

    /**
     * On the first join after startup, validates all stored displays once. Also schedules a delayed
     * `modRequired` message for vanilla clients when mod detection is enabled.
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        Scheduler.trackPlayer(player)
        WatchPartyManager.onPlayerJoin(player.uniqueId)

        if (!PlatformUtil.isFolia && !hasValidatedWorld && DisplayManager.getDisplays().isNotEmpty()) {
            hasValidatedWorld = true
            // A config with errors may name the wrong base material: checking against it would delete every display
            if (config.loadedWithErrors) {
                com.dreamdisplays.platform.server.PaperServer.logger.warn("Skipping the startup display check: config.toml has errors.")
            } else {
                Scheduler.runLater(40L) { DisplayManager.validateDisplaysAndCleanup() }
            }
        }

        if (!config.settings.modDetectionEnabled) return
        if (DisplayManager.getDisplays().isEmpty()) return

        Scheduler.runPlayerLater(player, 600L) {
            if (PlayerManager.getVersion(player) == null && !PlayerManager.hasBeenNotifiedAboutModRequired(player)) {
                ServerMessage.MOD_REQUIRED.sendTo(player)
                PlayerManager.setModRequiredNotified(player, true)
            }
        }
    }

    /**
     * A player who changed world ends any screen share they run (a display lives in one world). Their PiP-pinned
     * displays are announced again once the client has switched level: it drops carried PiPs nobody re-announces.
     */
    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        com.dreamdisplays.platform.server.utils.net.ScreenShareActions.onPlayerChangedWorld(event.player.uniqueId)
        val player = event.player
        Scheduler.runPlayerLater(player, PIN_REANNOUNCE_DELAY_TICKS) { PipPinManager.onPlayerJoin(player.uniqueId) }
    }

    /** Drops cached per-player state when a player disconnects. */
    @EventHandler
    fun onPlayerLeave(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        // Each step on its own: one failing must not leave the rest of the player's state behind
        quitStep("version") { PlayerManager.removeVersion(event.player) }
        quitStep("handshake") { V2PlayerTracker.clear(id) }
        quitStep("watch party") { WatchPartyManager.onPlayerQuit(id) }
        quitStep("screen share") { com.dreamdisplays.platform.server.utils.net.ScreenShareActions.onPlayerQuit(id) }
        quitStep("fullscreen") { FullscreenBroadcastManager.onPlayerQuit(id) }
        quitStep("readiness") { ReadinessTracker.forgetPlayer(id) }
        quitStep("selection") { SelectionManager.resetSelection(id) }
        quitStep("nearby") { DisplayManager.forgetNearbyPlayer(id) }
        quitStep("diagnostics") { com.dreamdisplays.platform.server.policy.ClientDiagnostics.onQuit(id) }
        quitStep("error reports") { com.dreamdisplays.platform.server.policy.ClientErrorReports.onQuit(id) }
        quitStep("karaoke") { com.dreamdisplays.platform.server.karaoke.KaraokeManager.onQuit(id) }
        quitStep("scheduler") { Scheduler.untrackPlayer(event.player) }
    }

    private fun quitStep(what: String, step: () -> Unit) {
        runCatching(step).onFailure {
            org.slf4j.LoggerFactory.getLogger(PlayerListener::class.java).warn("Player quit cleanup failed: {}.", what, it)
        }
    }
}

/**
 * Shared `Fabric` / `NeoForge` player join / leave handling. [FabricPlayerListener] and
 * [NeoForgePlayerConnectionListener] only adapt their loader's event-subscription API and hand off here.
 */
@ModLoaderOnly
object VanillaPlayerListener {
    private var hasValidatedWorld = false

    /**
     * On the first join after startup, validates all stored displays once. Also schedules a delayed
     * `modRequired` message for vanilla clients when mod detection is enabled.
     */
    fun onJoin(player: ServerPlayer, server: MinecraftServer) {
        WatchPartyManager.onPlayerJoin(player.uuid)
        if (!hasValidatedWorld && DisplayManager.getDisplays().isNotEmpty()) {
            hasValidatedWorld = true
            VanillaServerScheduler.runLater(server, 40L) { DisplayManager.validateDisplaysAndCleanup(server) }
        }

        if (!VanillaServerState.config.settings.modDetectionEnabled) return
        if (DisplayManager.getDisplays().isEmpty()) return

        VanillaServerScheduler.runLater(server, 600L) {
            if (player.isAlive &&
                PlayerManager.getVersion(player.uuid) == null &&
                !PlayerManager.hasBeenNotifiedAboutModRequired(player.uuid)
            ) {
                ServerMessage.MOD_REQUIRED.sendTo(player)
                PlayerManager.setModRequiredNotified(player.uuid, true)
            }
        }
    }

    /** Drops cached per-player state when a player disconnects. */
    fun onLeave(playerId: UUID) {
        PlayerManager.removePlayer(playerId)
        V2PlayerTracker.clear(playerId)
        WatchPartyManager.onPlayerQuit(playerId)
        FullscreenBroadcastManager.onPlayerQuit(playerId)
        ReadinessTracker.forgetPlayer(playerId)
        SelectionManager.resetSelection(playerId)
    }
}

// Forge adapter lives in :platform:client:forge

/** `Fabric` event-subscription adapter for [VanillaPlayerListener]. */
@FabricOnly
object FabricPlayerListener {
    fun register() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            VanillaPlayerListener.onJoin(handler.player, server)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            VanillaPlayerListener.onLeave(handler.player.uuid)
        }
    }
}

//? if >=1.21.1 {
/** `NeoForge` event-subscription adapter for [VanillaPlayerListener]. */
@NeoForgeOnly
object NeoForgePlayerConnectionListener {
    @SubscribeEvent
    fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        VanillaPlayerListener.onJoin(player, RegionUtil.playerServer(player))
    }

    @SubscribeEvent
    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        VanillaPlayerListener.onLeave(player.uuid)
    }
}
//?}
