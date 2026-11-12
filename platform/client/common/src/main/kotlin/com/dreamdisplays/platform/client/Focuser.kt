package com.dreamdisplays.platform.client

import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.managers.ClientStateManager
import net.minecraft.client.Minecraft

/** Background thread that temporarily mutes / unmutes screens based on window focus. */
class Focuser : Thread() {
    init {
        isDaemon = true
        instance = this
        name = "window-focus-mute-thread"
    }

    override fun run() {
        while (true) {
            if (MasterSwitch.on && ClientStateManager.config.muteOnAltTab) {
                val mc: Minecraft? = runCatching { Minecraft.getInstance() }.getOrNull()
                if (mc != null) {
                    val focused = mc.isWindowActive
                    for (screen in DisplayRegistry.getScreens()) {
                        screen.setFocusMuted(shouldFocusMute(focused, screen.hasWindowPopout))
                    }
                }
            }
            try {
                sleep(250)
            } catch (_: InterruptedException) {
                currentThread().interrupt()
                break
            }
        }
    }

    companion object {
        /**
         * True when a display goes quiet because the game window lost focus. Not one shown in its own popout window:
         * clicking into that window to watch it is what takes the focus away.
         */
        fun shouldFocusMute(gameWindowFocused: Boolean, inPopoutWindow: Boolean): Boolean =
            !gameWindowFocused && !inPopoutWindow

        /** The running focuser thread, or `null` before it is started. */
        @Volatile
        var instance: Focuser? = null
            private set
    }
}
