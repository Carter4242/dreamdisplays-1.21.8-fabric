package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.core.services.DisplaySystem
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.shared.storage.DisplayData
import com.dreamdisplays.core.services.DisplayStorage
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.render.ConformingWorldMesh
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Central registry of all live [DisplayScreen]s and the event bus for their lifecycle changes. */
object DisplayRegistry {
    /** Screens registered by the application. */
    val screens = ConcurrentHashMap<UUID, DisplayScreen>()

    /** Screens unregistered by the application, but still in memory. */
    val unloadedScreens = ConcurrentHashMap<UUID, DisplayData>()

    private val eventListeners = CopyOnWriteArrayList<(DisplayEvent) -> Unit>()
    private val displaySystem: DisplaySystem? get() = DreamServices.registry.getOrNull<DisplaySystem>()

    /** Returns a snapshot of all currently registered screens. */
    fun getScreens(): Collection<DisplayScreen> = screens.values

    /** Snapshot of screens currently parked fully warm, used by the adaptive warm-park budget. */
    fun dormantScreens(): List<DisplayScreen> = screens.values.filter { it.isDormant }

    /** Dispatches [event] to all registered listeners. */
    fun emit(event: DisplayEvent) {
        displaySystem?.publish(event) ?: eventListeners.forEach { it(event) }
    }

    /** Publishes the current screen snapshot into the application display system. */
    fun recordScreen(displayScreen: DisplayScreen) {
        if (!screens.containsKey(displayScreen.uuid)) return
        displaySystem?.recordDisplay(displayScreen.toDisplay())
    }

    /** Registers a new display screen. */
    fun registerScreen(displayScreen: DisplayScreen) {
        screens[displayScreen.uuid]?.unregister()

        val clientSettings = ClientSettingsStore.getSettings(
            displayScreen.uuid,
            DisplayScreen.defaultVolume(),
        )
        displayScreen.volume = clientSettings.volume
        displayScreen.quality = VideoQuality.parse(clientSettings.quality)
        displayScreen.muted = clientSettings.muted
        displayScreen.acousticsEnabled = clientSettings.acousticsEnabled
        // Every unload writes the resume position here (the session snapshot in DisplayStorage is only written by
        // saveScreenData, so after a dimension change it is the older of the two and must not win)
        displayScreen.savedTimeNanos = clientSettings.savedTimeNanos
        displayScreen.savedTimeUrl = clientSettings.savedTimeUrl

        screens[displayScreen.uuid] = displayScreen
        recordScreen(displayScreen)
        DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.registerSource(displayScreen.uuid)
    }

    /** Unregisters a display; caches world-anchored ones for distance-triggered re-load. */
    fun unregisterScreen(displayScreen: DisplayScreen) {
        ConformingWorldMesh.forget(displayScreen.uuid)
        if (displayScreen.virtual) {
            ClientSettingsStore.remove(displayScreen.uuid)
        } else {
            // One whose world is unknown (only ever described as forced) could come back in the wrong world
            if (DisplayDimensions.cacheableForRestore(displayScreen.dimensionKey)) {
                unloadedScreens[displayScreen.uuid] = displayScreen.toFullDisplayData()
            }
            ClientSettingsStore.setSavedTime(displayScreen.uuid, displayScreen.currentTimeNanos, displayScreen.videoUrl)
        }
        screens.remove(displayScreen.uuid)
        displayScreen.unregister()
        displaySystem?.removeDisplay(DisplayId(displayScreen.uuid))
        DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.unregisterSource(displayScreen.uuid)
    }

    /** Unregisters all display screens. */
    fun unloadAll() {
        val acoustics = DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)
        screens.values.forEach { it.unregister(); acoustics?.unregisterSource(it.uuid) }
        screens.clear()
        ConformingWorldMesh.clear()
        unloadedScreens.clear()
        awaitingReconfirm.clear()
        displaySystem?.clearDisplays()
    }

    private const val RECONFIRM_GRACE_MS = 12_000L
    private val awaitingReconfirm = ConcurrentHashMap<UUID, Long>()

    /**
     * Level-change teardown: world-anchored displays go immediately, popout-active ones (PiP, window, fullscreen) are
     * carried along. A dimension change on the same server keeps them for good: the server still knows them, and they
     * stop being drawn in the world they are not in. [newBackend] (a proxy moved the player to another server) holds
     * them only for the reconfirm grace, forgets their world, and also forgets the distance-restore cache, whose
     * entries belong to the previous server and would otherwise reappear at the same coordinates on this one.
     */
    fun unloadAllForServerSwitch(newBackend: Boolean) {
        val now = System.currentTimeMillis()
        val carried = mutableSetOf<UUID>()
        for (screen in screens.values.toList()) {
            if (screen.isPopoutActive) {
                carried += screen.uuid
                if (newBackend) {
                    awaitingReconfirm[screen.uuid] = now + RECONFIRM_GRACE_MS
                    screen.dimensionKey = ""
                }
            } else {
                // Per-display removal, never displaySystem.clearDisplays(): that one wipes every
                // display it knows about, and the removal event closes the very popout being carried.
                unregisterScreen(screen)
            }
        }
        if (newBackend) unloadedScreens.clear() else unloadedScreens.keys.removeAll(carried)
        if (newBackend) awaitingReconfirm.keys.retainAll(carried)
    }

    /** The new server re-announced [displayId]; it is no longer provisional. */
    fun markReconfirmed(displayId: UUID) {
        awaitingReconfirm.remove(displayId)
    }

    /** Drops carried-over displays the new server never re-announced. Called once per client tick. */
    fun tickReconfirm() {
        if (awaitingReconfirm.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((displayId, deadline) in awaitingReconfirm.entries.toList()) {
            if (deadline > now) continue
            awaitingReconfirm.remove(displayId)
            screens[displayId]?.let { unregisterScreen(it) }
        }
    }

    /** Saves the display screen data to disk. */
    fun saveScreenData(displayScreen: DisplayScreen) {
        DisplayStorage.saveDisplayData(displayScreen.uuid, displayScreen.toFullDisplayData())
        ClientSettingsStore.setSavedTime(displayScreen.uuid, displayScreen.currentTimeNanos, displayScreen.videoUrl)
    }

    /** Restores the display snapshot cached for [serverId] (e.g. saved timecodes) from a prior session. */
    fun loadScreensForServer(serverId: String) {
        DisplayStorage.load(serverId, DisplayStorage.snapshot(serverId))
    }

    /** Saves all display screens to disk. */
    fun saveAllScreens() {
        screens.values.forEach { saveScreenData(it) }
    }
}
