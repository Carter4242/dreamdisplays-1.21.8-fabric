package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if <1.20 {
import com.dreamdisplays.platform.client.ui.compat.GuiGraphics
//?}
*/

/**
 * Coordinator for the fullscreen video overlay. At most one overlay is live at a time (a newly
 * opened one fades the previous out); closing overlays keep rendering until their animation ends.
 * Rendered before [PipOverlayManager] in the HUD hooks so PiP stays on top.
 */
object FullscreenOverlayManager {
    private val overlays = mutableListOf<FullscreenOverlay>()
    private var screenWasOpen = false

    /** The single non-closing overlay, or null when none is active. */
    val active: FullscreenOverlay? get() = overlays.lastOrNull { !it.isClosing }

    /** True when any overlay (including a fading-out one) still needs rendering. */
    val isEmpty: Boolean get() = overlays.isEmpty()

    /**
     * True while the vanilla HUD (hotbar, health / hunger, chat, ...) should stay hidden behind a
     * fullscreen video.
     */
    val shouldHideVanillaHud: Boolean get() = !isEmpty

    /** True while an immersive-mode overlay is active; used to suppress the crosshair. */
    val isImmersiveActive: Boolean
        get() = active?.mode == FullscreenMode.IMMERSIVE

    /** Opens a fullscreen overlay for [screen], fading out any previous one. */
    fun open(
        screen: DisplayScreen,
        mode: FullscreenMode,
        forced: Boolean = false,
        sessionId: String? = null
    ): FullscreenOverlay {
        // Another display's fullscreen must end there too, not just fade: its popout state would otherwise stay
        // set, keeping that display decoding and heard everywhere (popout audio has no distance falloff)
        overlays.filter { !it.isClosing && it.displayScreen !== screen }
            .map { it.displayScreen }.distinct()
            .forEach { it.deactivateFullscreen() }
        overlays.forEach { it.startClose() }
        val overlay = FullscreenOverlay(screen, mode, forced, sessionId)
        overlays.add(overlay)
        return overlay
    }

    /**
     * Destroys [ds]'s overlays at once, with no close animation (which would keep showing their last frame while it
     * fades): a screen share that ended. Render thread; queued there when called from another.
     */
    fun removeNow(ds: DisplayScreen) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { removeNow(ds) }
            return
        }
        val gone = overlays.filter { it.displayScreen === ds }
        if (gone.isEmpty()) return
        gone.forEach { it.cleanup(mc) }
        overlays.removeAll(gone)
    }

    /** Starts the close animation for the active overlay belonging to [ds], if any. */
    fun remove(ds: DisplayScreen) {
        overlays.filter { it.displayScreen === ds }.forEach { it.startClose() }
    }

    /** Gracefully closes every overlay. */
    fun closeAll() {
        overlays.forEach { it.startClose() }
    }

    /** Immediately destroys all overlays without animation. */
    fun clear() {
        val mc = Minecraft.getInstance()
        overlays.forEach { it.cleanup(mc) }
        overlays.clear()
    }

    /**
     * Per-tick Esc handling: pressing Esc during gameplay opens the vanilla pause screen, so a `null -> pause screen`
     * transition while an overlay is active is treated as the user's Esc-close intent.
     */
    fun onClientTick(mc: Minecraft) {
        val screen = MinecraftScreenUtil.currentScreen(mc)
        val overlay = active
        // A pause screen opened while the window is unfocused is vanilla's pause-on-lost-focus (alt-tab), not Esc
        if (overlay != null && !screenWasOpen && screen is PauseScreen && mc.isWindowActive) {
            MinecraftScreenUtil.setScreen(mc, null)
            overlay.displayScreen.closeFullscreenByViewer()
        }
        screenWasOpen = screen != null
    }

    /** Uploads and renders all overlays, dropping ones whose close animation has finished. */
    //? if >=26 {
    fun renderAll(mc: Minecraft, graphics: GuiGraphicsExtractor, partialTick: Float) {
        //?} else
        /*fun renderAll(mc: Minecraft, graphics: GuiGraphics, partialTick: Float) {*/
        val iter = overlays.iterator()
        while (iter.hasNext()) {
            val overlay = iter.next()
            overlay.uploadFrame()
            if (!overlay.render(mc, graphics, partialTick)) iter.remove()
        }
    }
}
