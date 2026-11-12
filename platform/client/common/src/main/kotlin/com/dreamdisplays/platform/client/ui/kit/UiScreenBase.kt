package com.dreamdisplays.platform.client.ui.kit

import com.dreamdisplays.platform.client.guard.ClientGuard
import com.dreamdisplays.platform.client.guard.ScreenFailures
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
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
import net.minecraft.client.gui.screens.Screen
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * Base class for Dream Displays screens. It handles the per-frame state sync, operates in virtual
 * coordinates, and handles scaling to fit the real window.
 *
 * Every entry point the game calls runs under [ClientGuard]: an exception in a handler is logged and reported
 * instead of crashing the game. A failed click, scroll or key press is dropped (a key the screen's own handler
 * failed on still reaches vanilla, so Escape always closes); a render or tick that keeps failing, or an init that
 * fails, closes the screen ([ScreenFailures]). Subclasses override the `on*` hooks, never the entry points.
 */
abstract class UiScreenBase(title: Component) : Screen(title) {
    private val uiWidgets = ArrayList<UiWidget>()
    private val failures = ScreenFailures()
    private val screenName: String = javaClass.simpleName

    /**
     * Current downscale factor in (0, 1]; 1.0 means the window is large enough and nothing is scaled.
     * Mouse coordinates are divided by this to map real -> virtual space.
     */
    protected var uiScale: Double = 1.0
        private set

    /** Registers [w] as a renderable, interactive child and includes it in the per-frame state sync. */
    protected fun <T : UiWidget> addUi(w: T): T {
        uiWidgets.add(w)
        addRenderableWidget(w)
        return w
    }

    private fun site(entry: String): String = "$screenName.$entry"

    /** Builds the screen's widgets; called on open and whenever the screen is rebuilt (resize). */
    protected open fun onInit() {}

    /** Called when the screen goes away (closed or replaced). */
    protected open fun onRemoved() {}

    /** Called every client tick while the screen is open. */
    protected open fun onTick() {}

    final override fun init() {
        uiWidgets.clear()
        failures.reset()
        super.init()
        failures.initialized(ClientGuard.guard.attempt(site("init")) { onInit() })
    }

    final override fun tick() {
        if (failures.closeRequested) return closeAfterFailure()
        failures.ticked(ClientGuard.guard.attempt(site("tick")) {
            onTick()
            super.tick()
        })
    }

    final override fun removed() {
        ClientGuard.run(site("removed")) { onRemoved() }
        //? if >=1.21.6 <1.21.11 {
        // No vanilla cursor API yet: restore the arrow when the screen goes away so it never sticks in gameplay
        // or other screens
        ClientGuard.run(site("removed")) { LegacyCursor.reset() }
        //?}
        super.removed()
    }

    // Closing from tick is what vanilla's own self-closing screens do; from render or init it is not safe
    private fun closeAfterFailure() {
        logger.warn("Closing {} after repeated errors (logged above).", screenName)
        ClientGuard.run(site("close")) { onClose() }
    }

    /**
     * The minimum logical canvas this screen wants to lay out against, or null to never scale. When the
     * real window is smaller than this on either axis, the screen is rendered on a virtual canvas of at
     * least this size and scaled down to fit.
     */
    protected open fun minContentSize(): Pair<Int, Int>? = null

    /** Version-neutral render body: draw panels and custom content here, then call [drawChildren]. */
    protected abstract fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float)

    /** Maps a virtual X coordinate back to a real screen X (e.g. for tooltips, which render unscaled). */
    protected fun toRealX(virtualX: Int): Int = (virtualX * uiScale).toInt()

    /** Maps a virtual Y coordinate back to a real screen Y (e.g. for tooltips, which render unscaled). */
    protected fun toRealY(virtualY: Int): Int = (virtualY * uiScale).toInt()

    private fun computeScale(): Double {
        val (mw, mh) = minContentSize() ?: return 1.0
        if (mw <= 0 || mh <= 0) return 1.0
        return minOf(1.0, width.toDouble() / mw, height.toDouble() / mh)
    }

    /** One guarded frame: nothing is drawn once the screen is closing after errors. */
    private inline fun guardedFrame(draw: () -> Unit) {
        if (failures.closeRequested) return
        failures.rendered(ClientGuard.guard.attempt(site("render"), draw))
    }

    private fun renderScaled(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) {
        uiScale = computeScale()
        uiWidgets.forEach { it.syncState() }

        if (uiScale >= 1.0) {
            drawScreen(g, mouseX, mouseY, delta)
            return
        }

        val s = uiScale
        val realW = width
        val realH = height
        width = (realW / s).toInt()
        height = (realH / s).toInt()

        // A draw that throws must not leave the transform pushed or the virtual size in place
        val matrices = g.pose()
        //? if >=1.21.6 {
        matrices.pushMatrix()
        try {
            matrices.scale(s.toFloat(), s.toFloat())
            drawScreen(g, (mouseX / s).toInt(), (mouseY / s).toInt(), delta)
        } finally {
            matrices.popMatrix()
            width = realW
            height = realH
        }
        //?} else
        /*matrices.pushPose()
        try {
            matrices.scale(s.toFloat(), s.toFloat(), 1f)
            drawScreen(g, (mouseX / s).toInt(), (mouseY / s).toInt(), delta)
        } finally {
            matrices.popPose()
            width = realW
            height = realH
        }*/
    }

    //? if >=1.21.11 {
    private fun toVirtual(event: MouseButtonEvent): MouseButtonEvent =
        if (uiScale >= 1.0) event
        else MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo())

    /** Screen-specific click handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean = false

    /** Screen-specific release handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseReleased(event: MouseButtonEvent): Boolean = false

    final override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean =
        ClientGuard.call(site("mouseClicked"), true) {
            val e = toVirtual(event)
            onMouseClicked(e, doubleClick) || super.mouseClicked(e, doubleClick)
        }

    final override fun mouseReleased(event: MouseButtonEvent): Boolean =
        ClientGuard.call(site("mouseReleased"), true) {
            val e = toVirtual(event)
            onMouseReleased(e) || super.mouseReleased(e)
        }

    /** Screen-specific drag handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean = false

    final override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        ClientGuard.call(site("mouseDragged"), true) {
            val e = toVirtual(event)
            val dx = dragX / uiScale
            val dy = dragY / uiScale
            onMouseDragged(e, dx, dy) || super.mouseDragged(e, dx, dy)
        }

    /** Screen-specific key handling (hotkeys). Return true to consume; false passes the key on to vanilla. */
    protected open fun onKeyPressed(event: KeyEvent): Boolean = false

    final override fun keyPressed(event: KeyEvent): Boolean =
        ClientGuard.call(site("keyPressed"), false) { onKeyPressed(event) } ||
            ClientGuard.call(site("keyPressed"), true) { super.keyPressed(event) }

    final override fun keyReleased(event: KeyEvent): Boolean =
        ClientGuard.call(site("keyReleased"), true) { super.keyReleased(event) }

    final override fun charTyped(event: CharacterEvent): Boolean =
        ClientGuard.call(site("charTyped"), true) { super.charTyped(event) }
    //?} else
    /*protected open fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    protected open fun onMouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    final override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean =
        ClientGuard.call(site("mouseClicked"), true) {
            val mx = mouseX / uiScale
            val my = mouseY / uiScale
            onMouseClicked(mx, my, button) || super.mouseClicked(mx, my, button)
        }

    final override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean =
        ClientGuard.call(site("mouseReleased"), true) {
            val mx = mouseX / uiScale
            val my = mouseY / uiScale
            onMouseReleased(mx, my, button) || super.mouseReleased(mx, my, button)
        }

    protected open fun onMouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean = false

    final override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        ClientGuard.call(site("mouseDragged"), true) {
            val mx = mouseX / uiScale
            val my = mouseY / uiScale
            onMouseDragged(mx, my, button, dragX / uiScale, dragY / uiScale) ||
                super.mouseDragged(mx, my, button, dragX / uiScale, dragY / uiScale)
        }

    // Screen-specific key handling (hotkeys). Return true to consume; false passes the key on to vanilla.
    protected open fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    final override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        ClientGuard.call(site("keyPressed"), false) { onKeyPressed(keyCode, scanCode, modifiers) } ||
            ClientGuard.call(site("keyPressed"), true) { super.keyPressed(keyCode, scanCode, modifiers) }

    final override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        ClientGuard.call(site("keyReleased"), true) { super.keyReleased(keyCode, scanCode, modifiers) }

    final override fun charTyped(chr: Char, modifiers: Int): Boolean =
        ClientGuard.call(site("charTyped"), true) { super.charTyped(chr, modifiers) }*/

    /** Screen-specific scroll handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        false

    //? if >=1.20.2 {
    final override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        ClientGuard.call(site("mouseScrolled"), true) {
            val mx = mouseX / uiScale
            val my = mouseY / uiScale
            onMouseScrolled(mx, my, scrollX, scrollY) || super.mouseScrolled(mx, my, scrollX, scrollY)
        }
    //?} else
    /*final override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean =
        ClientGuard.call(site("mouseScrolled"), true) {
            val mx = mouseX / uiScale
            val my = mouseY / uiScale
            onMouseScrolled(mx, my, 0.0, delta) || super.mouseScrolled(mx, my, delta)
        }*/

    final override fun mouseMoved(mouseX: Double, mouseY: Double) =
        ClientGuard.run(site("mouseMoved")) { super.mouseMoved(mouseX / uiScale, mouseY / uiScale) }

    //? if >=26 {
    final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) =
        guardedFrame { renderScaled(g, mouseX, mouseY, delta) }

    /** Draws the standard translucent screen background. */
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = extractTransparentBackground(g)

    /** Renders all registered child widgets (the vanilla `super` pass). */
    protected fun drawChildren(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) =
        super.extractRenderState(g, mouseX, mouseY, delta)
    //?} else
    /*
    //? if >=1.20 <1.21.6 {
    final override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) =
        guardedFrame { renderScaled(g, mouseX, mouseY, delta) }
    //?}
    //? if >=1.21.6 <1.21.11 {
    // No vanilla cursor API yet: show the shape widgets requested this frame (or the default arrow)
    final override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) =
        guardedFrame {
            renderScaled(g, mouseX, mouseY, delta)
            LegacyCursor.applyFrame()
        }
    //?}
    //? if >=1.21.11 {
    final override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) =
        guardedFrame { renderScaled(g, mouseX, mouseY, delta) }
    //?}
    //? if <1.20 {
    final override fun render(pose: com.mojang.blaze3d.vertex.PoseStack, mouseX: Int, mouseY: Int, delta: Float) =
        guardedFrame { renderScaled(GuiGraphics(pose), mouseX, mouseY, delta) }
    //?}

    // Draws the standard translucent screen background.
    //? if >=1.20.2 {
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = renderTransparentBackground(g)
    //?}
    //? if >=1.20 <1.20.2 {
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = renderBackground(g)
    //?}
    //? if <1.20 {
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = renderBackground(g.pose())
    //?}

    // Renders all registered child widgets (the vanilla `super` pass).
    //? if >=1.20 {
    protected fun drawChildren(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) =
        super.render(g, mouseX, mouseY, delta)
    //?}
    //? if <1.20 {
    protected fun drawChildren(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) =
        super.render(g.pose(), mouseX, mouseY, delta)
    //?}

    //? if >=1.20.2 <1.21.6 {
    // 1.21.1 strange background remover
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {}
    //?}
    */

    private companion object {
        private val logger = LoggerFactory.getLogger(UiScreenBase::class.java)
    }
}
