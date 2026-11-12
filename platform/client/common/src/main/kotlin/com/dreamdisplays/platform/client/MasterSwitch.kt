package com.dreamdisplays.platform.client

import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.media.player.process.ScreenCaptureProcesses
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayQueues
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.ScreenShare
import com.dreamdisplays.platform.client.displays.ViewerReporter
import com.dreamdisplays.platform.client.karaoke.KaraokeClient
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.managers.ClientStartupManager
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.managers.DisplayLifecycleManager
import com.dreamdisplays.platform.client.managers.DisplayWarmup
import com.dreamdisplays.platform.client.managers.FullscreenController
import com.dreamdisplays.platform.client.managers.FullscreenPrepareController
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplays.shared.capability.ServerFeature
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.*

/**
 * The "Dream Displays" On / Off row of the settings screen (`mod-enabled` in the config): Off stops everything the mod
 * does on this client, so a player can find out whether it is what makes their game lag. Only the player changes it;
 * nothing a server sends can.
 *
 * Off: a screen share ends, a karaoke singer leaves the line, the server is told displays are off here (the
 * `SetDisplaysEnabled` of `/display off`, so starts stop waiting for this client), and every display is unloaded with
 * all it holds (decoders, `FFmpeg`, sound, read-ahead, warm and parked players, popouts, PiP and fullscreen). While off,
 * every hook (ticks, render passes, HUD, input, mixins, background loops) returns on [on] before any work, and of what
 * the server sends only the latest description of each display is kept ([held]), its policy, deletions and share
 * answers applied; the rest is dropped ([MasterSwitchRules.offHandling]).
 *
 * On: the server hears this server's own `/display on|off` state again, and every kept description goes the normal
 * way, as if the player had just walked into range (each loaded display asks the server for its timeline).
 *
 * It is a layer over `/display off|on` ([ClientStateManager.displaysEnabled], one per server): the server hears
 * displays are on only when both are ([MasterSwitchRules.displaysOnForServer]). A `/display on` while this is Off is
 * kept for that server but answered "off", and the player is told why.
 */
object MasterSwitch {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** False while the player switched the mod off. Every hook reads it first; written on the client thread only. */
    @Volatile
    @JvmField
    var on: Boolean = true

    /** The latest description of each display while displays are off here (also while off by `/display off`). */
    internal val held = HeldDescriptions()

    /** Displays whose karaoke stage this client left while off (once each per off period). */
    private val stagesLeft = HashSet<UUID>()

    /** The value at start, from the config; nothing else is switched then (nothing runs yet). */
    fun load(config: Config) {
        on = config.modEnabled
        if (!on) logger.info("Dream Displays is switched off in its settings: it loads no display and starts nothing.")
    }

    /** The player switched the mod [enabled] (the settings screen): saved, and applied at once. */
    fun set(enabled: Boolean) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { set(enabled) }
            return
        }
        val config = ClientStateManager.config
        if (config.modEnabled != enabled) {
            config.modEnabled = enabled
            config.save()
        }
        if (on == enabled) return
        if (enabled) switchOn() else switchOff()
    }

    private fun switchOff() {
        logger.info("Dream Displays switched off in its settings: unloading every display and stopping all its work.")
        on = false
        // The capture first (privacy), then what the server waits for
        runCatching { ScreenShare.stop() }
        runCatching { ScreenCaptureProcesses.killAll() }
        runCatching { KaraokeClient.stopForSwitchOff() }.getOrDefault(emptySet()).forEach(::leaveStage)
        tellServer()

        // Every display's last description, to come back from; then everything that runs for a display goes
        DisplayLifecycleManager.lastDescriptions().forEach(held::keepIfAbsent)
        for (screen in DisplayRegistry.getScreens().toList()) {
            runCatching {
                DisplayRegistry.saveScreenData(screen)
                DisplayRegistry.unregisterScreen(screen)
            }.onFailure { logger.warn("Could not unload display {} cleanly.", screen.uuid, it) }
        }
        DisplayRegistry.unloadAll()
        // A decoder that was still connected when its player stopped kept its read-ahead buffer and streamer alive
        runCatching { com.dreamdisplays.media.player.readahead.ReadAhead.closeAll() }
        DisplayLifecycleManager.clearInfo()
        runCatching { com.dreamdisplays.platform.client.displays.DisplayReplayCache.clear() }
        com.dreamdisplays.platform.client.displays.RecentTimelines.reset()
        DisplayQueues.reset()
        FullscreenController.reset()
        FullscreenPrepareController.reset()
        DisplayWarmup.reset()
        ViewerReporter.reset()
        DreamServices.registry.getOrNull<OverlayManager>()?.closeAll()
        FullscreenOverlayManager.closeAll()
        ClientStateManager.isOnScreen = false
        logger.info("Dream Displays is off; {} display description(s) kept for when it is switched back on.", held.size)
    }

    private fun switchOn() {
        logger.info("Dream Displays switched back on in its settings.")
        on = true
        stagesLeft.clear()
        ClientStartupManager.startBackgroundWork()
        tellServer()
        if (ProtocolRouter.v2Negotiated) ScreenShare.prewarm()
        if (ClientStateManager.displaysEnabled) DisplayLifecycleManager.replayHeld()
    }

    /** Tells the server whether displays are on here now (nothing before it answered the hello). */
    private fun tellServer() {
        Initializer.sendPacket(
            SetDisplaysEnabled(MasterSwitchRules.displaysOnForServer(on, ClientStateManager.displaysEnabled))
        )
    }

    /** A timeline arrived while off: a karaoke lead in it means this player is singing there, so they leave. */
    internal fun onTimelineWhileOff(sync: DisplaySync) {
        if (MasterSwitchRules.leavesStage(sync, stagesLeft)) leaveStage(sync.id)
    }

    /**
     * The server switched displays [enabled] for this player (`/display on|off`) while the mod is off: the server's
     * switch is kept, but an "on" is answered "off", since nothing will show, and the player hears why.
     */
    internal fun onServerSetWhileOff(enabled: Boolean) {
        val answer = MasterSwitchRules.answerToServerSet(on, enabled) ?: return
        Initializer.sendPacket(SetDisplaysEnabled(answer))
        DisplayQueues.notifyLocal(
            com.dreamdisplays.platform.client.ui.ClientMessage.MESSAGE_SWITCHED_OFF.component()
                .withStyle(net.minecraft.ChatFormatting.YELLOW)
        )
    }

    /**
     * Takes this player off [displayId]'s karaoke stage the way they would themselves (`/display karaoke leave`), once
     * per off period: a singer whose client plays nothing would otherwise keep singing to a track they cannot hear.
     */
    private fun leaveStage(displayId: UUID) {
        if (!stagesLeft.add(displayId)) return
        if (!ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.KARAOKE)) return
        logger.info("Leaving the karaoke stage on display {} (Dream Displays switched off).", displayId)
        val player = Minecraft.getInstance().player ?: return
        //? if >=1.19.4 {
        player.connection.sendCommand("display karaoke leave $displayId")
        //?} else
        /*player.chat("/display karaoke leave $displayId")*/
    }

    /** Disconnect / another backend: what was kept belongs to that server. */
    internal fun resetForServer() {
        held.clear()
        stagesLeft.clear()
    }
}
