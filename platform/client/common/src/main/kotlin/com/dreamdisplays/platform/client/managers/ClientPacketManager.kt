package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.core.services.DisplaySystem
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.core.protocol.common.packets.*
import com.dreamdisplays.core.services.DisplayStorage
import com.dreamdisplays.platform.client.DisplaysSwitch
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.Mod
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplays.platform.client.net.compat.CustomPacketPayload*/
import org.slf4j.LoggerFactory

/**
 * Single client-side dispatcher for incoming [DreamPacket]s and the
 * raw payload sender bound to the platform [Mod] implementation.
 */
object ClientPacketManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var mod: Mod

    /** The latest applied [ServerHello]. */
    @Volatile
    var serverSnapshot: ServerHello = ServerHello(); private set

    /** Binds the platform [Mod] used to send packets. */
    fun bind(mod: Mod) {
        this.mod = mod
    }

    /** Sends a raw payload through the platform networking implementation. */
    fun send(packet: CustomPacketPayload) {
        if (com.dreamdisplays.platform.client.replay.ReplayHooks.interceptSend(packet)) return
        mod.sendPacket(packet)
    }

    /** Applies an incoming packet to the client state; non-client-bound packets are ignored. */
    fun handle(packet: DreamPacket) {
        when (packet) {
            is ServerHello -> applyServerHello(packet)
            is SetDisplaysEnabled -> applyDisplaysEnabled(packet.enabled)
            is DisplayInfo -> DisplayLifecycleManager.handleInfoPacket(packet)
            is DisplaySync -> {
                // A timeline for a video this client was not told about: ask for the display again (and still follow
                // the timeline meanwhile, so pauses and seeks keep applying). Not while displays are off: the answer
                // would only be recorded, and the display then never asked for again once they are back on
                if (ClientStateManager.displaysEnabled) {
                    com.dreamdisplays.platform.client.displays.ServerMediaGens.onSync(packet)
                }
                com.dreamdisplays.platform.client.displays.RecentTimelines.record(
                    packet,
                    DisplayRegistry.screens[packet.id]?.videoUrl ?: DisplayRegistry.unloadedScreens[packet.id]?.videoUrl,
                )
                DisplayRegistry.screens[packet.id]?.let {
                    it.updateData(packet)
                    DisplayRegistry.recordScreen(it)
                }
            }

            is FullscreenState -> FullscreenController.handle(packet)
            is FullscreenPrepare -> FullscreenPrepareController.handle(packet)
            is RemotePlaybackToggle -> DisplayRegistry.screens[packet.id]?.applyScheduledPaused(packet.paused)
            is DisplayDelete -> handleDelete(packet)
            is ClearCache -> handleClearCache(packet)
            is ServerPolicy -> com.dreamdisplays.platform.client.displays.ServerOverrides.apply(packet)
            is QueueState -> com.dreamdisplays.platform.client.displays.DisplayQueues.apply(packet)
            is ActivityState -> com.dreamdisplays.platform.client.displays.DisplayPanels.apply(packet)
            is ViewersState -> com.dreamdisplays.platform.client.displays.DisplayPanels.apply(packet)
            is KaraokeClockPing -> com.dreamdisplays.platform.client.karaoke.KaraokeClient.onClockPing(packet)
            is ScreenShareState -> com.dreamdisplays.platform.client.displays.ScreenShare.onState(packet)
            is ReportRequest -> com.dreamdisplays.platform.client.displays.ReportRequests.handle(packet)
            else -> logger.debug("Ignoring non-client-bound packet {}.", packet::class.simpleName)
        }
    }

    private fun applyServerHello(packet: ServerHello) {
        serverSnapshot = packet
        ClientStateManager.isPremium = packet.isPremium
        ClientStateManager.isAdmin = packet.isAdmin
        ClientStateManager.isReportingEnabled = packet.isReportingEnabled
        com.dreamdisplays.platform.client.displays.ClientErrorReporter.onServerHello(packet)
        DreamServices.registry.getOrNull<CapabilityNegotiationService>()
            ?.onServerCapabilities(packet)
        Initializer.sendPacket(SetDisplaysEnabled(ClientStateManager.displaysEnabled))
        // A hello on a connection that already had one is a reloaded server, which forgot every report: send them again
        com.dreamdisplays.platform.client.displays.ReportRequests.onServerHello()
        com.dreamdisplays.platform.client.displays.DisplayPanels.onServerHello()
        com.dreamdisplays.platform.client.displays.ScreenShare.prewarm()
    }

    /** The server switched displays on or off for this player: for this server only (see [DisplaysSwitch]). */
    private fun applyDisplaysEnabled(enabled: Boolean) {
        ClientStateManager.displaysEnabled = enabled
        val config = ClientStateManager.config
        val (global, disabled) = DisplaysSwitch.afterServerSet(
            config.displaysEnabled, config.displaysDisabledServers, ClientStateManager.connectedServerId, enabled,
        )
        if (global == config.displaysEnabled && disabled == config.displaysDisabledServers) return
        config.displaysEnabled = global
        config.displaysDisabledServers = disabled
        config.save()
    }

    private fun handleDelete(packet: DisplayDelete) {
        com.dreamdisplays.platform.client.displays.ServerMediaGens.forget(packet.id)
        com.dreamdisplays.platform.client.displays.RecentTimelines.forget(packet.id)
        DisplayRegistry.screens[packet.id]?.let { DisplayRegistry.unregisterScreen(it) }
        DisplayRegistry.unloadedScreens.remove(packet.id)
        DisplayLifecycleManager.forgetInfo(packet.id)
        com.dreamdisplays.platform.client.displays.DisplayRegions.forget(packet.id)
        DisplayStorage.removeDisplay(packet.id)
        logger.info("Display deleted and removed from saved data: ${packet.id}.")
    }

    private fun handleClearCache(packet: ClearCache) {
        packet.ids.forEach { uuid ->
            com.dreamdisplays.platform.client.displays.ServerMediaGens.forget(uuid)
            com.dreamdisplays.platform.client.displays.RecentTimelines.forget(uuid)
            DisplayRegistry.screens[uuid]?.let { DisplayRegistry.unregisterScreen(it) }
            DisplayRegistry.unloadedScreens.remove(uuid)
            DisplayLifecycleManager.forgetInfo(uuid)
            com.dreamdisplays.platform.client.displays.DisplayRegions.forget(uuid)
            DreamServices.registry.getOrNull<DisplaySystem>()?.removeDisplay(DisplayId(uuid))
            DisplayStorage.removeDisplay(uuid)
        }
    }

    /** Resets per-server negotiation state on disconnect. */
    fun reset() {
        serverSnapshot = ServerHello()
        com.dreamdisplays.platform.client.displays.DisplayPolicyCache.clear()
        com.dreamdisplays.platform.client.displays.DisplayRegions.clear()
        com.dreamdisplays.platform.client.displays.ServerMediaGens.reset()
        com.dreamdisplays.platform.client.displays.RecentTimelines.reset()
        com.dreamdisplays.platform.client.displays.ReportRequests.reset()
        DisplayLifecycleManager.clearInfo()
        com.dreamdisplays.platform.client.displays.ServerOverrides.reset()
        com.dreamdisplays.platform.client.displays.ViewerReporter.reset()
        com.dreamdisplays.platform.client.displays.ClientErrorReporter.reset()
        com.dreamdisplays.platform.client.displays.DisplayQueues.reset()
        com.dreamdisplays.platform.client.displays.DisplayPanels.reset()
        com.dreamdisplays.platform.client.karaoke.KaraokeClient.reset()
        FullscreenController.reset()
        FullscreenPrepareController.reset()
        DisplayWarmup.reset()
    }
}
