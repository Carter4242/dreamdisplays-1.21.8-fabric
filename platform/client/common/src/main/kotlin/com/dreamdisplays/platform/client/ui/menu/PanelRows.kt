package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
//? if <1.21.6 {
import com.dreamdisplays.platform.client.ui.enableScissorPoseAware
//?}
import com.dreamdisplays.platform.client.ui.kit.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A scrolling list of one-line rows in the display menu's bottom panel (the Activity and Viewers tabs), in the
 * Up next cards' style: dark row plates, a lighter one under the mouse, a thin scrollbar. Drawing, hit-testing and
 * scrolling only; [drawRow] paints each row's content and may return a tooltip for the hovered one.
 */
abstract class PanelRows {
    private var scrollF = 0f
    private var targetScroll = 0
    private var lastFrameNanos = 0L
    private var maxScroll = 0
    private var strip: UiRect? = null

    /** Tooltip lines for the row under the mouse this frame, or null. */
    var tooltip: List<String>? = null
        private set

    /** How many rows there are this frame (0 shows [emptyText] instead). */
    protected abstract fun rowCount(): Int

    /** What the panel says when there are no rows (or they are still loading). */
    protected abstract fun emptyText(): String

    /** Paints row [index] into [r]; returns its tooltip lines when [hover]. */
    protected abstract fun drawRow(g: GuiGraphicsCompat, f: Font, index: Int, r: UiRect, hover: Boolean): List<String>?

    /** Draws the panel into [panel]; the header line is left to the screen's tabs. */
    fun render(g: GuiGraphicsCompat, panel: UiRect, mouseX: Int, mouseY: Int) {
        g.drawPanelSprite(panel)
        tooltip = null
        val f = Minecraft.getInstance().font
        val stripRect = UiRect(panel.x + 10, panel.y + 10 + HEADER_H + 6, panel.w - 22, panel.h - 10 - HEADER_H - 6 - 10)
        strip = stripRect
        val count = rowCount()
        if (count == 0) {
            maxScroll = 0
            var y = stripRect.y + 4
            for (line in UiText.wrap(f, emptyText(), stripRect.w, 3)) {
                g.drawText(f, line, stripRect.x, y, UiTheme.TEXT_SECONDARY, false)
                y += f.lineHeight + 2
            }
            return
        }
        val rowH = f.lineHeight + ROW_PAD * 2
        val step = rowH + ROW_GAP
        maxScroll = max(0, count * step - ROW_GAP - stripRect.h)
        targetScroll = targetScroll.coerceIn(0, maxScroll)
        scrollF = ease(scrollF.coerceIn(0f, maxScroll.toFloat()), targetScroll.toFloat())
        val scroll = scrollF.roundToInt()

        //? if >=1.21.6 {
        g.enableScissor(stripRect.x, stripRect.y, stripRect.right, stripRect.bottom)
        //?} else
        /*g.enableScissorPoseAware(stripRect.x, stripRect.y, stripRect.right, stripRect.bottom)*/
        val first = max(0, scroll / step)
        val last = min(count - 1, (scroll + stripRect.h) / step)
        for (i in first..last) {
            val r = UiRect(stripRect.x, stripRect.y + i * step - scroll, stripRect.w, rowH)
            val hover = r.contains(mouseX, mouseY) && stripRect.contains(mouseX, mouseY)
            g.fill(r.x, r.y, r.right, r.bottom, if (hover) UiTheme.CARD_BG_HOVER else UiTheme.CARD_BG)
            drawRow(g, f, i, r, hover)?.let { if (hover) tooltip = it }
        }
        g.disableScissor()
        drawScrollbar(g, stripRect, scroll)
    }

    /** Scrolls the list; true when the mouse is over it. */
    fun handleScroll(mouseX: Int, mouseY: Int, dy: Double): Boolean {
        val r = strip ?: return false
        if (!r.contains(mouseX, mouseY)) return false
        targetScroll = (targetScroll - (dy * 24).toInt()).coerceIn(0, maxScroll)
        return true
    }

    /** True when ([mouseX], [mouseY]) is over the list (clicks there must not reach the panel underneath). */
    fun contains(mouseX: Int, mouseY: Int): Boolean = strip?.contains(mouseX, mouseY) == true

    private fun drawScrollbar(g: GuiGraphicsCompat, r: UiRect, scroll: Int) {
        if (maxScroll <= 0) return
        val viewport = r.h
        val content = maxScroll + viewport
        val barX = r.right + 2
        g.fill(barX, r.y, barX + 2, r.bottom, UiTheme.SCROLLBAR_TRACK)
        val len = max(20, (viewport.toFloat() / content * viewport).toInt())
        val pos = r.y + (scroll.toFloat() / maxScroll * (viewport - len)).toInt()
        g.fill(barX, pos, barX + 2, pos + len, UiTheme.SCROLLBAR_THUMB)
    }

    private fun ease(current: Float, target: Float): Float {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        val diff = target - current
        if (abs(diff) < 0.05f) return target
        return current + diff * min(1f, dt * 8f)
    }

    companion object {
        private const val HEADER_H = 14
        const val ROW_PAD = 3
        private const val ROW_GAP = 2
    }
}
