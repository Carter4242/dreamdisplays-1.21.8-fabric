package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.audio.ListenerPoseTracker
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.core.ClientApplication
import com.dreamdisplays.platform.client.core.ClientLifecycleEvent
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.ScreenShare
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.platform.client.displays.ViewerReporter
import com.dreamdisplays.platform.client.input.*
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.render.ShaderPackCompat
import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplays.platform.client.net.ProtocolRouter
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import java.util.*

/**
 * Handles per-tick client display state: level changes, hover, unloading, and shortcuts.
 */
object ClientTickManager {
    private const val DORMANT_HYSTERESIS_BLOCKS = 4
    private var wasInMultiplayer = false

    @Volatile
    private var lastLevel: ClientLevel? = null
    private var lastConnection: ClientPacketListener? = null
    private var unloadCheckTick = 0
    private var lastRenderPath: RenderPath? = null
    private var hoveredDisplayScreen: DisplayScreen? = null
    private var lastHoveredId: UUID? = null
    private var tickCount = 0L

    /** Main per-tick update: level changes, hover, render-distance (un)loading, and the menu shortcut. */
    fun tick(minecraft: Minecraft) {
        tickCount++
        DreamServices.registry.getOrNull<ClientApplication>()
            ?.emit(ClientLifecycleEvent.Tick(tickCount))

        FullscreenOverlayManager.onClientTick(minecraft)
        FullscreenController.onClientTick()
        DisplayWarmup.onClientTick()
        FullscreenPrepareController.onClientTick()
        DisplayRegistry.tickReconfirm()
        if (minecraft.level != null) {
            ProtocolRouter.tickHelloRefresh(DisplayRegistry.getScreens().any { !it.virtual && !it.isDormant })
        }
        runCatching { com.dreamdisplays.platform.client.karaoke.KaraokeClient.tick() }
        // Screen share: capture watchdog, display / range checks and heartbeat, every tick (in a world or not)
        ScreenShare.tick(minecraft)

        val level = minecraft.level
        if (level != null && (minecraft.currentServer != null || minecraft.isLocalServer)) {
            val connection = minecraft.connection
            val newBackend = connection != null && lastConnection != null && connection !== lastConnection
            if (connection != null) lastConnection = connection
            if (lastLevel == null) {
                lastLevel = level
                if (newBackend) {
                    // Before the hello below, so the new backend is negotiated from scratch
                    Initializer.onBackendSwitched()
                    DisplayRegistry.unloadAllForServerSwitch(newBackend = true)
                    hoveredDisplayScreen = null
                }
                checkVersionAndSendPacket()
            }
            if (level !== lastLevel) {
                lastLevel = level
                // Another dimension or another backend: a running screen share ends here
                ScreenShare.onLevelChanged()
                if (newBackend) Initializer.onBackendSwitched()
                DisplayRegistry.unloadAllForServerSwitch(newBackend)
                hoveredDisplayScreen = null
                checkVersionAndSendPacket()
            }
            wasInMultiplayer = true
        } else {
            val screen = MinecraftScreenUtil.currentScreen(minecraft)
            val leftForGood = screen is TitleScreen || screen is JoinMultiplayerScreen
            if (wasInMultiplayer && leftForGood) {
                wasInMultiplayer = false
                ScreenShare.onServerLeft()
                DisplayRegistry.unloadAll()
                DreamServices.registry.getOrNull<OverlayManager>()?.closeAll()
                FullscreenOverlayManager.closeAll()
                hoveredDisplayScreen = null
                lastLevel = null
                lastConnection = null
                return
            }
            lastLevel = null
            return
        }

        // Display under the crosshair, resolved through the DisplayInteractionService contract
        // (replaces the inline RayCastingUtil + isInScreen mapping this manager used to duplicate).
        val hoveredId = DreamServices.registry.getOrNull<DisplayInteractionService>()
            ?.getCurrentTarget()?.displayId?.uuid
        notifyHoverChange(hoveredId)
        hoveredDisplayScreen = null
        ClientStateManager.isOnScreen = false
        val player = minecraft.player ?: return
        val playerPos = player.blockPosition()
        DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)
            ?.updateListener(ListenerPoseTracker.currentPose(minecraft))

        rebuildDisplaysOnShaderPackChange(playerPos)

        unloadCheckTick++
        if (unloadCheckTick >= 10 && ClientStateManager.displaysEnabled && ServerOverrides.displaysEnabled &&
            DisplayRegistry.unloadedScreens.isNotEmpty()
        ) {
            unloadCheckTick = 0
            DisplayLifecycleManager.restoreVisibleUnloadedScreens(playerPos)
        }

        val overBudget = DisplayLifecycleManager.overBudget(playerPos)
        for (displayScreen in DisplayRegistry.getScreens()) {
            if (displayScreen.isVirtualAndDone) {
                DisplayRegistry.unregisterScreen(displayScreen)
                continue
            }
            // Hysteresis
            val threshold = if (displayScreen.isDormant) {
                displayScreen.renderDistance - DORMANT_HYSTERESIS_BLOCKS
            } else {
                displayScreen.renderDistance + DORMANT_HYSTERESIS_BLOCKS
            }
            // A display in another world is out of range whatever its coordinates
            val outOfRange = !displayScreen.isInWorldHere ||
                    threshold < displayScreen.getDistanceToScreen(playerPos) || displayScreen in overBudget
            // Switched off by the server: everything goes, popouts and virtual displays included
            val serverDisabled = !ServerOverrides.displayEnabled(displayScreen.policy)
            // A display held for a fullscreen broadcast stays loaded at any distance until the broadcast takes it
            val shouldUnload = serverDisabled || ((outOfRange || !ClientStateManager.displaysEnabled) &&
                    !displayScreen.isPopoutActive && !displayScreen.virtual && !displayScreen.isPrepared)

            // Already parked warm: wake it when back in range, or tear it down once it has been dormant
            // past the pool TTL (freeing its decoder + texture; the snapshot cache then bridges a return).
            // demoteAfterNanos is not a second, earlier TTL here — it only makes a display a preferred
            // eviction victim in reserveWarmSlot once a newer candidate needs its slot (see below).
            if (displayScreen.isDormant) {
                if (!shouldUnload) displayScreen.wake()
                else if (serverDisabled || displayScreen.dormantExpired(WarmParkPolicy.ttlNanos)) compressDormant(displayScreen)
                continue
            }

            if (shouldUnload) {
                // Keep a bounded pool of Local VOD displays warm (decoder + audio open, frozen), so walking
                // back is instant. Older / out-of-budget warm parks are compressed into replay snapshots.
                // Only the natural "left render distance" case parks; disabling displays tears down fully.
                val warmEligible = outOfRange && ClientStateManager.displaysEnabled && !serverDisabled
                if (warmEligible && displayScreen.canWarmPark() && reserveWarmSlot(displayScreen)) {
                    displayScreen.goDormant()
                } else {
                    DisplayRegistry.saveScreenData(displayScreen)
                    DisplayRegistry.unregisterScreen(displayScreen)
                }
                if (hoveredDisplayScreen === displayScreen) {
                    hoveredDisplayScreen = null
                    ClientStateManager.isOnScreen = false
                }
            } else {
                if (displayScreen.uuid == hoveredId) {
                    hoveredDisplayScreen = displayScreen
                    ClientStateManager.isOnScreen = true
                }
                displayScreen.tick(playerPos)
            }
        }

        ViewerReporter.tick()
        com.dreamdisplays.platform.client.displays.ClientErrorReporter.tick()

        // The sneak + use menu shortcut itself fires from the UseItemMenu mixin, which also cancels the use.
        DisplayMenuInputHandler.tickUseKey(minecraft)
    }

    private fun compressDormant(displayScreen: DisplayScreen) {
        DisplayRegistry.saveScreenData(displayScreen)
        DisplayRegistry.unregisterScreen(displayScreen)
    }

    private fun reserveWarmSlot(candidate: DisplayScreen): Boolean {
        if (WarmParkPolicy.maxFullWarmDisplays <= 0) return false
        repeat(WarmParkPolicy.maxFullWarmDisplays + 1) {
            val dormant = DisplayRegistry.dormantScreens()
            if (WarmParkPolicy.fits(dormant, candidate)) return true
            val victim = dormant.filter { it.dormantExpired(WarmParkPolicy.demoteAfterNanos) }
                .minByOrNull { it.dormantSinceNanos() }
                ?: dormant.minByOrNull { it.dormantSinceNanos() }
                ?: return false
            compressDormant(victim)
        }
        return false
    }

    private fun notifyHoverChange(hoveredId: UUID?) {
        if (hoveredId == lastHoveredId) return
        val service = DreamServices.registry.getOrNull<DisplayInteractionService>()
        lastHoveredId?.let { service?.emit(DisplayInteraction.LookedAway(DisplayId(it))) }
        hoveredId?.let { service?.emit(DisplayInteraction.Looked(DisplayId(it))) }
        lastHoveredId = hoveredId
    }

    private fun checkVersionAndSendPacket() {
        DreamServices.registry.getOrNull<CapabilityNegotiationService>()?.advertise()
    }

    /** What a player fixes when it is created: its drawing, decoding, upload and sound paths. */
    private data class RenderPath(
        val shaderPack: Boolean,
        val decoder: com.dreamdisplays.core.display.policy.DisplayPolicy.VideoDecoder,
        val gpuYuv: Boolean,
        val persistentUploads: Boolean,
        val audioBackend: String,
    )

    /**
     * A display picks its drawing path (GPU YUV or plain RGBA), decoder, texture uploads and sound output when its player
     * is created, and a shader pack can only draw the RGBA one. When shaders are switched on or off mid-session, or the
     * server switches one of those paths (`client.videoDecoder`, `gpuYuv`, `textureUpload`, `audioBackend`), rebuild
     * every loaded display through the same save-and-restore path used when the server turns displays off and on, so
     * each reopens on the right path.
     */
    private fun rebuildDisplaysOnShaderPackChange(playerPos: net.minecraft.core.BlockPos) {
        val active = runCatching { ShaderPackCompat.isShaderPackActive }.getOrDefault(false)
        val server = ServerOverrides.settings
        val path = RenderPath(
            active, server.videoDecoder, server.gpuYuv, server.persistentUploads,
            server.audioBackend(ClientStateManager.config.audioBackend),
        )
        val previous = lastRenderPath
        lastRenderPath = path
        if (previous == null || previous == path) return
        val screens = DisplayRegistry.getScreens().filter { !it.isPopoutActive && !it.virtual }
        // Kept up (a PiP, window or fullscreen of it, or a broadcast-only display): not torn down, but given a new player
        // in place, its popouts staying open, when its current one is on a path that no longer applies
        val serverPathChanged = previous.copy(shaderPack = active) != path
        val rebuiltInPlace = DisplayRegistry.getScreens().filter {
            RenderPathRules.newPlayerInPlace(
                shadersTurnedOn = active && !previous.shaderPack, serverPathChanged = serverPathChanged,
                keptUp = it.isPopoutActive || it.virtual, yuvTexture = it.isYuvTexture, alpha = it.videoHasAlpha,
            )
        }
        if (screens.isEmpty() && rebuiltInPlace.isEmpty()) return
        org.slf4j.LoggerFactory.getLogger(ClientTickManager::class.java)
            .info("Playback path changed ({}); reloading {} display(s) on it, {} in place.", path, screens.size, rebuiltInPlace.size)
        rebuiltInPlace.forEach { it.rebuildPlayerInPlace() }
        for (screen in screens) {
            DisplayRegistry.saveScreenData(screen)
            DisplayRegistry.unregisterScreen(screen)
        }
        if (ClientStateManager.displaysEnabled && ServerOverrides.displaysEnabled) {
            DisplayLifecycleManager.restoreVisibleUnloadedScreens(playerPos)
        }
    }
}
