package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
import com.dreamdisplays.platform.client.ui.kit.renderChild
//? if <1.20.2 {
import com.dreamdisplays.platform.client.ui.blitSprite
//?}
//? if >=1.21.6 <1.21.11 {
import com.dreamdisplays.platform.client.ui.kit.LegacyCursor
//?}
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import com.dreamdisplays.platform.client.utils.emptyText
import com.dreamdisplays.platform.client.utils.literalText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?}
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?}

/**
 * Playback-speed control styled like the settings sliders: the slider track with the speed centred ("1.0x"). Clicking
 * it turns it into a text box (highlighted track, caret): type a speed, Enter or clicking away applies it, clamped to
 * [PlaybackSpeed.MIN]..[PlaybackSpeed.MAX]; anything that is not a number puts the current speed back. While not
 * being edited it shows [current], so a speed set by someone else (or by the reset button) shows up at once.
 */
class SpeedField(
    private val current: () -> Double,
    private val onCommit: (Double) -> Unit,
) : UiWidget(emptyText()) {
    private val box: EditBox = EditBox(Minecraft.getInstance().font, 0, 0, 60, 20, emptyText())
    private var shown = Double.NaN
    private var wasFocused = false

    init {
        box.setMaxLength(MAX_CHARS)
        box.setBordered(false) // The track sprite is the frame, as on the slider rows
        box.setFilter { text -> text.all { it.isDigit() || it == '.' || it == ',' || it == 'x' || it == 'X' } }
        box.value = PlaybackSpeed.format(current())
    }

    override fun handlesWholeWidgetCursor(): Boolean = false

    /** True while the box has keyboard focus, so screen-level hotkeys must stand aside. */
    val isTyping: Boolean get() = box.isFocused

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (width <= 0 || height <= 0) return
        // Focus can move to another widget without this one hearing about it: treat that as clicking away
        if (box.isFocused && Minecraft.getInstance().screen?.focused !== this) blur()
        if (wasFocused && !box.isFocused) commit()
        wasFocused = box.isFocused
        if (!active && box.isFocused) blur()

        val speed = current()
        if (!box.isFocused && speed != shown) {
            shown = speed
            box.value = PlaybackSpeed.format(speed)
        }
        box.setEditable(active)
        box.setTextColor(TEXT_EDITING)

        val editing = box.isFocused
        val track = if (editing || (active && isHovered)) ValueSlider.TRACK_HIGHLIGHTED else ValueSlider.TRACK
        //? if >=1.21.6 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, track, x, y, width, height)
        //?} else
        /*g.blitSprite(track, x, y, width, height)*/

        val font = Minecraft.getInstance().font
        if (editing) {
            // Edit look: darker inset behind the text, the text centred with its caret
            g.fill(x + 2, y + 2, x + width - 2, y + height - 2, EDIT_INSET)
            val textW = font.width(box.value) + CARET_ROOM
            val boxW = textW.coerceAtMost(width - 8)
            box.x = x + (width - boxW) / 2
            box.y = y + (height - font.lineHeight) / 2 + 1
            box.width = boxW
            box.height = font.lineHeight
            box.renderChild(g, mouseX, mouseY, partialTick)
        } else {
            // Keep the hidden box over the label so a click places the caret where the text is
            val textW = font.width(box.value) + CARET_ROOM
            box.x = x + (width - textW) / 2
            box.y = y + (height - font.lineHeight) / 2 + 1
            box.width = textW
            box.height = font.lineHeight
            val color = if (active) 0xFFFFFF else 0xA0A0A0
            drawScrollingLabel(g, literalText(label(speed)).copy().withStyle { it.withColor(color) }, 2)
        }
        if (isHovered) {
            //? if >=1.21.11 {
            g.requestCursor(if (active) CursorTypes.IBEAM else CursorTypes.NOT_ALLOWED)
            //?}
            //? if >=1.21.6 <1.21.11 {
            (if (active) LegacyCursor.IBEAM else LegacyCursor.NOT_ALLOWED).request()
            //?}
        }
    }

    private fun commit() {
        val parsed = PlaybackSpeed.parse(box.value)
        val speed = parsed ?: current()
        shown = speed
        box.value = PlaybackSpeed.format(speed)
        if (parsed != null && parsed != current()) onCommit(parsed)
    }

    private fun focusBox(focused: Boolean) {
        //? if >=1.19.4 {
        box.isFocused = focused
        //?}
        //? if <1.19.4 {
        box.setFocus(focused)
        //?}
    }

    private fun blur() {
        if (!box.isFocused) return
        focusBox(false)
        wasFocused = false
        commit()
    }

    private fun isEnter(key: Int): Boolean = key == KEY_ENTER || key == KEY_KP_ENTER

    //? if >=1.21.11 {
    override fun mouseClicked(event: MouseButtonEvent, dbl: Boolean): Boolean {
        if (!active || !visible || !isMouseOver(event.x(), event.y())) {
            blur()
            return false
        }
        box.mouseClicked(event, dbl) // Places the text cursor
        focusBox(true)
        wasFocused = true
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!box.isFocused) return super.keyPressed(event)
        if (isEnter(event.key())) {
            blur()
            return true
        }
        return box.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (box.isFocused) return box.charTyped(event)
        return super.charTyped(event)
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!active || !visible || !isMouseOver(mouseX, mouseY)) {
            blur()
            return false
        }
        box.mouseClicked(mouseX, mouseY, button) // Places the text cursor
        focusBox(true)
        wasFocused = true
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!box.isFocused) return super.keyPressed(keyCode, scanCode, modifiers)
        if (isEnter(keyCode)) {
            blur()
            return true
        }
        return box.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (box.isFocused) return box.charTyped(chr, modifiers)
        return super.charTyped(chr, modifiers)
    }*/

    private companion object {
        const val MAX_CHARS = 6
        const val KEY_ENTER = 257 // GLFW_KEY_ENTER
        const val KEY_KP_ENTER = 335 // GLFW_KEY_KP_ENTER
        const val TEXT_EDITING = 0xFFFFFFFF.toInt()
        const val EDIT_INSET = 0xA0000000.toInt()
        const val CARET_ROOM = 6 // The caret ("_" at the end) draws just past the text

        /** "1.0x": the unit only on the shown label; the text box holds the bare number while editing. */
        fun label(speed: Double): String = PlaybackSpeed.format(speed) + "x"
    }
}
