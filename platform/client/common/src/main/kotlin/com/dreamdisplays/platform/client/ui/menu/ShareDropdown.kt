package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.utils.buttonClickSound
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
//? if <1.21.6 {
import com.dreamdisplays.platform.client.ui.enableScissorPoseAware
//?}
import com.dreamdisplays.platform.client.ui.kit.*
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One line of the share picker. */
sealed interface ShareRow {
    /** A section title ("Screens", "Windows", "Sound"). */
    data class Header(val text: String) : ShareRow

    /** A line that cannot be picked ("Audio not supported on macOS"). */
    data class Note(val text: String) : ShareRow

    /**
     * Something to pick. [onPick] returns the next page's rows (the picker stays open on them) or null (done, it
     * closes). [selected] marks the default.
     */
    data class Item(val label: String, val selected: Boolean = false, val onPick: () -> List<ShareRow>?) : ShareRow
}

/**
 * The "Share screen" popup that opens above the share button: what to share (screens, then windows), then what it
 * sounds like. Its rows are read when it opens, so a monitor plugged in or a window opened since shows up; a long
 * list scrolls (wheel or scrollbar).
 */
class ShareDropdown(
    private val listRows: () -> List<ShareRow>,
) : PopupLayer {
    override var visible: Boolean = false

    private var rows: List<ShareRow> = []

    /** Wide enough for the longest label (screen names carry their place and size), within limits. */
    private var width = MIN_WIDTH
    private var rect = UiRect(0, 0, width, ITEM_H)
    private var viewH = 0
    private var contentH = 0
    private var scrollTarget = 0f
    private var scrollPx = 0f
    private var animProgress = 0f
    private var lastFrameNanos = 0L
    private var draggingScrollbar = false

    /** Toggles dropdown visibility, re-reading the rows when it opens. */
    fun toggle() {
        if (!visible) show(listRows()) else hide()
    }

    private fun show(next: List<ShareRow>) {
        rows = next
        val font = Minecraft.getInstance().font
        val longest = rows.maxOfOrNull {
            when (it) {
                is ShareRow.Item -> font.width(it.label)
                is ShareRow.Header -> font.width(it.text)
                is ShareRow.Note -> font.width(it.text)
            }
        } ?: 0
        width = ShareDropdownSizing.widthFor(longest)
        scrollTarget = 0f
        scrollPx = 0f
        visible = rows.any { it is ShareRow.Item }
    }

    /** Hides the dropdown. */
    override fun hide() {
        visible = false
        draggingScrollbar = false
    }

    private fun heightOf(row: ShareRow): Int = if (row is ShareRow.Item) ITEM_H else HEADER_H

    private fun maxScroll(): Float = (contentH - viewH).coerceAtLeast(0).toFloat()

    /** The row at content offset [y] (pixels from the top of the list), or -1. */
    private fun rowAt(y: Float): Int {
        var top = 0
        rows.forEachIndexed { i, row ->
            val h = heightOf(row)
            if (y >= top && y < top + h) return i
            top += h
        }
        return -1
    }

    /**
     * Draws the dropdown anchored above ([anchorCenterX], [anchorY]), horizontally centered on that
     * point (the button's own center) rather than growing out from one of its edges — easing in / out
     * of [visible]. It never grows past the top of the screen (what does not fit scrolls), and is
     * shifted sideways to stay inside a screen [screenW] wide, so no label is cut off at its edge.
     */
    fun draw(g: GuiGraphicsCompat, anchorCenterX: Int, anchorY: Int, mouseX: Int, mouseY: Int, screenW: Int = Int.MAX_VALUE) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now

        val target = if (visible) 1f else 0f
        animProgress += (target - animProgress) * minOf(1f, dt * 12f)
        if (animProgress < 0.01f) {
            animProgress = 0f
            return
        }
        if (rows.isEmpty()) return

        contentH = rows.sumOf(::heightOf)
        viewH = minOf(contentH, MAX_LIST_H, (anchorY - 2 - PAD_V * 2 - SCREEN_MARGIN).coerceAtLeast(ITEM_H))
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll())
        val diff = scrollTarget - scrollPx
        scrollPx = if (abs(diff) < 0.05f) scrollTarget else scrollPx + diff * minOf(1f, dt * SCROLL_EASE_RATE)
        val paged = contentH > viewH
        val height = viewH + PAD_V * 2
        val w = width.coerceAtMost((screenW - 2 * SCREEN_MARGIN).coerceAtLeast(MIN_WIDTH))
        rect = UiRect(ShareDropdownSizing.placeX(anchorCenterX, w, screenW, SCREEN_MARGIN), anchorY - height - 2, w, height)

        val scale = 0.85f + 0.15f * animProgress
        val matrices = g.pose()
        //? if >=1.21.6 {
        matrices.pushMatrix()
        matrices.translate(rect.centerX.toFloat(), rect.centerY.toFloat())
        matrices.scale(scale, scale)
        matrices.translate(-rect.centerX.toFloat(), -rect.centerY.toFloat())
        //?} else
        /*matrices.pushPose()
        matrices.translate(rect.centerX.toDouble(), rect.centerY.toDouble(), 0.0)
        matrices.scale(scale, scale, 1f)
        matrices.translate(-rect.centerX.toDouble(), -rect.centerY.toDouble(), 0.0)*/

        g.drawPanelSprite(rect, DROPDOWN_SPRITE, animProgress)

        val rowTop = rect.y + PAD_V
        val rowBottom = rowTop + viewH
        val inner = rect.x + BORDER
        val textRight = if (paged) rect.right - SCROLLBAR_W - 2 else rect.right - BORDER
        val hovered = if (visible && !draggingScrollbar && mouseX in inner until textRight && mouseY in rowTop until rowBottom)
            rowAt(mouseY - rowTop + scrollPx).takeIf { it >= 0 && rows[it] is ShareRow.Item } ?: -1 else -1

        val font = Minecraft.getInstance().font
        //? if >=1.21.6 {
        g.enableScissor(rect.x, rowTop, rect.right, rowBottom)
        //?} else
        /*g.enableScissorPoseAware(rect.x, rowTop, rect.right, rowBottom)*/
        var y = 0
        rows.forEachIndexed { i, row ->
            val h = heightOf(row)
            val itemY = (rowTop - scrollPx + y).roundToInt()
            y += h
            if (itemY + h < rowTop || itemY > rowBottom) return@forEachIndexed
            val textW = ShareDropdownSizing.labelRoom(rect.w, paged)
            when (row) {
                is ShareRow.Header -> g.drawText(
                    font, UiText.trim(font, row.text, textW), inner + 2, itemY + h - font.lineHeight,
                    scaleAlpha(UiTheme.ACCENT_VERSION, animProgress), false,
                )

                is ShareRow.Note -> g.drawText(
                    font, UiText.trim(font, row.text, textW), inner + 4, itemY + (h - font.lineHeight) / 2 + 1,
                    scaleAlpha(UiTheme.TEXT_SECONDARY, animProgress), false,
                )

                is ShareRow.Item -> {
                    if (i == hovered) {
                        g.fill(inner, itemY, textRight, itemY + h, scaleAlpha(UiTheme.HOVER_FILL, animProgress))
                        g.drawOutline(
                            UiRect(inner, itemY, textRight - inner, h),
                            scaleAlpha(UiTheme.CARD_BORDER_HOVER, animProgress),
                        )
                    } else if (row.selected) {
                        g.fill(inner, itemY, textRight, itemY + h, scaleAlpha(UiTheme.ACTIVE_ROW_FILL, animProgress))
                    }
                    val color = if (i == hovered || row.selected) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM
                    g.drawText(
                        font, UiText.trim(font, row.label, textW), inner + 4, itemY + (h - font.lineHeight) / 2,
                        scaleAlpha(color, animProgress), false,
                    )
                }
            }
        }
        g.disableScissor()

        if (paged) drawScrollbar(g, rect.right, rowTop, rowBottom)

        //? if >=1.21.11 {
        if (visible && hovered >= 0 && animProgress > 0.5f) g.requestCursor(CursorTypes.POINTING_HAND)
        if (visible && (draggingScrollbar || overScrollbar(mouseX, mouseY)) && animProgress > 0.5f) g.requestCursor(CursorTypes.RESIZE_NS)
        //?}
        //? if >=1.21.6 <1.21.11 {
        if (visible && hovered >= 0 && animProgress > 0.5f) LegacyCursor.POINTING_HAND.request()
        if (visible && (draggingScrollbar || overScrollbar(mouseX, mouseY)) && animProgress > 0.5f) LegacyCursor.RESIZE_NS.request()
        //?}

        //? if >=1.21.6 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
    }

    private fun thumbH(): Int = max(MIN_THUMB_H, viewH * viewH / contentH.coerceAtLeast(1))

    private fun drawScrollbar(g: GuiGraphicsCompat, right: Int, top: Int, bottom: Int) {
        val trackX = right - SCROLLBAR_W
        g.fill(trackX, top, trackX + 2, bottom, scaleAlpha(UiTheme.SCROLLBAR_TRACK, animProgress))
        val thumb = thumbH()
        val travel = (bottom - top) - thumb
        val thumbY = top + if (maxScroll() > 0f) (travel * scrollPx / maxScroll()).roundToInt() else 0
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumb, scaleAlpha(UiTheme.SCROLLBAR_THUMB, animProgress))
    }

    private fun overScrollbar(mx: Int, my: Int): Boolean =
        contentH > viewH && mx in (rect.right - SCROLLBAR_W - SB_GRAB)..(rect.right + 1) &&
                my in (rect.y + PAD_V)..(rect.y + PAD_V + viewH)

    override fun contains(mx: Int, my: Int): Boolean =
        (mx in rect.x..rect.right && my in rect.y..rect.bottom) || overScrollbar(mx, my)

    /**
     * Handles a left click while visible: picks an item if the click is on one (moving on to its next page, or
     * closing), starts a scrollbar drag on the scrollbar, and otherwise closes. Returns true if the click was used.
     */
    override fun handleClick(mx: Int, my: Int): Boolean {
        if (!visible) return false
        if (overScrollbar(mx, my)) {
            draggingScrollbar = true
            scrollToY(my)
            return true
        }
        val inside = mx in rect.x..rect.right && my in rect.y..rect.bottom
        if (!inside) {
            hide()
            return false
        }
        val index = rowAt(my - (rect.y + PAD_V) + scrollPx)
        // Headers and notes are not choices: the popup stays as it is
        val item = rows.getOrNull(index) as? ShareRow.Item ?: return true
        val s = SimpleSoundInstance.forUI(buttonClickSound(), 1.0f)
        Minecraft.getInstance().soundManager.play(s)
        val next = item.onPick()
        if (next != null) show(next) else hide()
        return true
    }

    /** A mouse-wheel tick over the open list: scrolls by a row per notch. Returns true if consumed. */
    fun handleScroll(mx: Int, my: Int, scrollY: Double): Boolean {
        if (!visible || contentH <= viewH || !rect.contains(mx, my)) return false
        if (scrollY != 0.0) scrollTarget = (scrollTarget + (if (scrollY > 0) -ITEM_H else ITEM_H)).coerceIn(0f, maxScroll())
        return true
    }

    /** Continues a scrollbar drag started in [handleClick]. */
    fun handleDrag(my: Int): Boolean {
        if (!draggingScrollbar) return false
        scrollToY(my)
        return true
    }

    /** Ends any scrollbar drag. Returns true if one was active (so the release is consumed). */
    fun handleRelease(): Boolean {
        val was = draggingScrollbar
        draggingScrollbar = false
        return was
    }

    private fun scrollToY(my: Int) {
        val travel = viewH - thumbH()
        if (travel <= 0 || maxScroll() <= 0f) return
        val rel = (my - (rect.y + PAD_V) - thumbH() / 2).coerceIn(0, travel)
        scrollTarget = rel.toFloat() / travel * maxScroll()
        scrollPx = scrollTarget
    }

    companion object {
        private const val MIN_WIDTH = ShareDropdownSizing.MIN_WIDTH
        private const val ITEM_H = 16
        private const val HEADER_H = 12
        private const val MAX_LIST_H = 176
        private const val BORDER = ShareDropdownSizing.BORDER
        private const val PAD_V = 2
        private const val SCROLLBAR_W = ShareDropdownSizing.SCROLLBAR_W
        private const val MIN_THUMB_H = 6
        private const val SB_GRAB = 3
        private const val SCREEN_MARGIN = 4
        private const val SCROLL_EASE_RATE = 8f
    }
}

/** How wide the share picker is and where it sits (pure, so it can be tested without a game). */
object ShareDropdownSizing {
    const val MIN_WIDTH = 150
    const val MAX_WIDTH = 320
    const val BORDER = 3
    const val SCROLLBAR_W = 5

    /** Pixels between the border and a label, each side (labels start at border + 4; the right side keeps 6). */
    private const val TEXT_INSET = 4

    /**
     * The width that fits a label [longestLabel] pixels wide: the border and text inset each side plus room for the
     * scrollbar a long list shows, so the label is never trimmed, up to [MAX_WIDTH].
     */
    fun widthFor(longestLabel: Int): Int =
        (longestLabel + 2 * BORDER + 2 * TEXT_INSET + SCROLLBAR_W + 2).coerceIn(MIN_WIDTH, MAX_WIDTH)

    /** Pixels a label gets in a popup [width] wide (as drawn: with the scrollbar's room taken when [paged]). */
    fun labelRoom(width: Int, paged: Boolean): Int =
        (if (paged) width - SCROLLBAR_W - 2 else width - BORDER) - BORDER - 6

    /** Left edge of a popup [w] wide centered on [anchorCenterX], moved inside [margin]..[screenW] - [margin]. */
    fun placeX(anchorCenterX: Int, w: Int, screenW: Int, margin: Int): Int {
        val max = screenW - margin - w
        return if (max < margin) margin else (anchorCenterX - w / 2).coerceIn(margin, max)
    }
}
