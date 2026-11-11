package com.dreamdisplays.platform.client.ui.widgets

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Card sizing for the bottom panel's horizontal strips (Suggestions and Up next): the strip height picks the thumbnail
 * height, the thumbnail height picks the card width. Pure math so the layout can be unit-tested.
 */
object StripCardMetrics {
    const val CARD_W = 152
    const val THUMB_H = 86
    const val MIN_THUMB_H = 30
    const val MIN_CARD_W = 80

    /** Title lines + channel line under the thumbnail. */
    const val CARD_TEXT_H = 32

    /** Suggestions: strip height spent on things other than the thumbnail (card text and paddings). */
    const val SUGGESTIONS_NON_THUMB_H = 2 + 3 + CARD_TEXT_H + 2 + 6

    /** Up next: strip height spent on things other than the thumbnail. */
    const val QUEUE_NON_THUMB_H = CARD_TEXT_H + 6 + 8

    /** Up next: panel height above and below its strip (title line, gaps, bottom padding). */
    const val QUEUE_PANEL_CHROME_H = 10 + 14 + 8 + 10

    /** Side-column (vertical) cards fill the column; their thumbnail is 16:9 of that width, at least [THUMB_H]. */
    fun verticalThumbH(cardW: Int): Int = max(THUMB_H, (cardW * 180.0 / 320.0).toInt())

    fun thumbH(stripH: Int, nonThumbH: Int): Int = max(MIN_THUMB_H, min(THUMB_H, stripH - nonThumbH))

    fun cardW(thumbH: Int): Int {
        if (thumbH >= THUMB_H) return CARD_W
        return max(MIN_CARD_W, (thumbH * CARD_W / THUMB_H.toDouble()).toInt())
    }

    /** The thumbnail's width on a [cardW]-wide card: as wide as the card unless the card hit [MIN_CARD_W]. */
    fun thumbW(thumbH: Int, cardW: Int): Int = (thumbH.toDouble() * CARD_W / THUMB_H).roundToInt().coerceAtMost(cardW)
}
