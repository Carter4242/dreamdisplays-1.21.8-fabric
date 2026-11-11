package com.dreamdisplays.platform.client.popout

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.media.sink.VideoFrameSink
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.core.protocol.common.packets.PipPin
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.DisplayPopoutManager
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global [PopoutManager] facade. Delegates per-display window and PiP operations to the [DisplayPopoutManager] owned by
 * each [DisplayScreen], fanning out their events to subscribers.
 */
class DefaultPopoutManager : PopoutManager {
    private val listeners = CopyOnWriteArrayList<(PopoutEvent) -> Unit>()

    override fun openWindow(displayId: DisplayId, config: WindowConfig): VideoFrameSink? {
        replacingPip(displayId) { it.activateWindowMode() }
        return null
    }

    override fun openPip(displayId: DisplayId): VideoFrameSink? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.activatePipMode()
        // Remembered (and reopened on every join) only when it actually opened
        if (screen.isPipActive) {
            ClientSettingsStore.setPipOpen(displayId.uuid, true)
            Initializer.sendPacket(PipPin(displayId.uuid, pinned = true))
        }
        return null
    }

    /** Runs [open], which closes the display's PiP when it succeeds; a PiP it replaced is forgotten like a closed one. */
    private fun replacingPip(displayId: DisplayId, open: (com.dreamdisplays.platform.client.displays.DisplayScreen) -> Unit) {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return
        val hadPip = screen.isPipActive
        open(screen)
        if (hadPip && !screen.isPipActive) {
            ClientSettingsStore.setPipOpen(displayId.uuid, false)
            Initializer.sendPacket(PipPin(displayId.uuid, pinned = false))
        }
    }

    override fun close(displayId: DisplayId) {
        val wasPip = isPipOpen(displayId)
        DisplayRegistry.screens[displayId.uuid]?.deactivatePopout()
        if (wasPip) {
            ClientSettingsStore.setPipOpen(displayId.uuid, false)
            Initializer.sendPacket(PipPin(displayId.uuid, pinned = false))
        }
    }

    override fun openFullscreen(displayId: DisplayId, mode: FullscreenMode) {
        replacingPip(displayId) { it.activateFullscreenMode(mode) }
    }

    override fun closeFullscreen(displayId: DisplayId) {
        DisplayRegistry.screens[displayId.uuid]?.deactivateFullscreen()
    }

    override fun isFullscreenOpen(displayId: DisplayId): Boolean =
        DisplayRegistry.screens[displayId.uuid]?.isFullscreenActive == true

    override fun closeAll() {
        DisplayRegistry.getScreens().forEach { it.deactivatePopout() }
        DreamServices.registry.getOrNull<OverlayManager>()?.closeAll()
    }

    override fun isWindowOpen(displayId: DisplayId): Boolean {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return false
        return screen.isPopoutActive && !isPipOpen(displayId) && !isFullscreenOpen(displayId)
    }

    override fun isPipOpen(displayId: DisplayId): Boolean =
        DreamServices.registry.getOrNull<OverlayManager>()?.getOverlay(displayId) != null

    override fun getPopoutSink(displayId: DisplayId): VideoFrameSink? = null

    override fun on(listener: (PopoutEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun emit(event: PopoutEvent) {
        listeners.forEach { it(event) }
    }
}
