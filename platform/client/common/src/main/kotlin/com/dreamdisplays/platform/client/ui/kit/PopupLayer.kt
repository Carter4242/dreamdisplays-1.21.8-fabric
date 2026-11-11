package com.dreamdisplays.platform.client.ui.kit

import com.dreamdisplays.platform.client.input.MouseButtons

/** A popup (dropdown) drawn above a screen's widgets. */
interface PopupLayer {
    /** True while the popup is open. */
    val visible: Boolean

    /** True if ([mx], [my]) lies on the popup, including any scrollbar grab zone. */
    fun contains(mx: Int, my: Int): Boolean

    /** A left click while open: acts on whatever is under the cursor; closes the popup unless it starts a scrollbar drag. */
    fun handleClick(mx: Int, my: Int): Boolean

    /** Closes the popup. */
    fun hide()
}

/**
 * Gives an open popup first claim on a click, before anything drawn beneath it. A left click inside acts on
 * the popup, other buttons inside do nothing, and any click outside only closes it; either way the click
 * goes no further. Returns false (not claimed) when the popup is closed or the click is on [onToggle], the
 * button that opens it, which handles the click itself.
 */
fun PopupLayer.claimClick(mx: Int, my: Int, button: Int, onToggle: Boolean): Boolean {
    if (!visible || onToggle) return false
    if (MouseButtons.isLeft(button)) handleClick(mx, my) else if (!contains(mx, my)) hide()
    return true
}

/** True when a scroll at ([mx], [my]) is over this open popup, which swallows it so nothing beneath scrolls. */
fun PopupLayer.claimScroll(mx: Int, my: Int): Boolean = visible && contains(mx, my)
