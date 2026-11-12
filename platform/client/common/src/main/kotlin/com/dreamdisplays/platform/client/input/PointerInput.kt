package com.dreamdisplays.platform.client.input

import com.dreamdisplays.platform.client.MasterSwitch
import com.dreamdisplays.core.input.PointerRouter
import com.dreamdisplays.core.input.PointerTarget
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft

/**
 * Gives our overlays (PiP) first claim on raw mouse input, ahead of the open screen and the game.
 * Called from the `MouseInput` mixin at the head of vanilla's mouse button and scroll callbacks; a true
 * return cancels vanilla's handling, so a click on an overlay never also reaches the screen or the world
 * beneath it. Overlays only take input while a screen has freed the cursor.
 */
object PointerInput {
    private const val ACTION_RELEASE = 0
    private const val ACTION_PRESS = 1

    private val router = PointerRouter()

    /** Handles a mouse button event for [window]; returns true to cancel vanilla's handling. */
    fun onButton(window: Long, button: Int, action: Int): Boolean =
        window == windowHandle(Minecraft.getInstance()) && onButton(button, action)

    /** Handles a mouse button event for the game window (loader events that already filtered it). */
    fun onButton(button: Int, action: Int): Boolean {
        if (!MasterSwitch.on) return false
        val mc = Minecraft.getInstance()
        return when (action) {
            ACTION_PRESS -> {
                val free = pointerFree(mc)
                router.press(button, scaledX(mc), scaledY(mc), free, if (free) targets() else emptyList())
            }

            ACTION_RELEASE -> {
                if (!router.isCaptured(button)) return false
                val free = pointerFree(mc)
                router.release(button, if (free) scaledX(mc) else Double.NaN, if (free) scaledY(mc) else Double.NaN)
            }

            else -> false
        }
    }

    /** Handles a scroll event for [window]; returns true to cancel vanilla's handling. */
    fun onScroll(window: Long): Boolean =
        window == windowHandle(Minecraft.getInstance()) && onScroll()

    /** Handles a scroll event for the game window (loader events that already filtered it). */
    fun onScroll(): Boolean {
        if (!MasterSwitch.on) return false
        val mc = Minecraft.getInstance()
        return pointerFree(mc) && router.scroll(scaledX(mc), scaledY(mc), true, targets())
    }

    /** Once per frame: drops captures whose release never arrived (e.g. the window lost focus mid-click). */
    fun reconcile() = router.reconcile(MouseButtons::isDown)

    private fun targets(): List<PointerTarget> =
        DreamServices.registry.getOrNull<OverlayManager>()?.pointerTargets ?: emptyList()

    private fun pointerFree(mc: Minecraft): Boolean =
        MinecraftScreenUtil.currentScreen(mc) != null && !mc.mouseHandler.isMouseGrabbed

    private fun windowHandle(mc: Minecraft): Long =
        //? if >=1.21.11 {
        mc.window.handle()
        //?} else
        /*mc.window.window*/

    //? if >=1.21.11 {
    private fun scaledX(mc: Minecraft): Double = mc.mouseHandler.getScaledXPos(mc.window)
    private fun scaledY(mc: Minecraft): Double = mc.mouseHandler.getScaledYPos(mc.window)
    //?} else
    /*private fun scaledX(mc: Minecraft): Double =
        mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.screenWidth
    private fun scaledY(mc: Minecraft): Double =
        mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.screenHeight*/
}
