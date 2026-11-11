package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.widgets.StripCardMetrics
import com.dreamdisplays.platform.client.ui.widgets.SuggestionsPanel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MenuLayoutTest {
    private val lineHeight = 9

    /** Settings panel: title line, five rows, then the owner action buttons in the bottom corner. */
    private val settingsNeededH =
        UiTheme.PANEL_PADDING_Y + lineHeight + 6 + 5 * (UiTheme.ROW_H + UiTheme.ROW_GAP) + UiTheme.CONTROL_BUTTON +
            UiTheme.PANEL_PADDING_Y

    private fun assertFullWidth169(thumbW: Int, thumbH: Int, cardW: Int, where: String) {
        assertTrue(abs(thumbW - cardW) <= 1, "$where: thumbnail $thumbW px wide on a $cardW px card")
        val ratio = thumbW.toDouble() / thumbH
        assertTrue(abs(ratio - 16.0 / 9.0) < 0.03, "$where: thumbnail $thumbW x $thumbH is not 16:9 ($ratio)")
    }

    private fun assertHorizontalCards(panel: UiRect, where: String) {
        // Suggestions tab: search row above the strip
        val sugStripH = panel.h - SuggestionsPanel.STRIP_CHROME_H
        val sugThumbH = StripCardMetrics.thumbH(sugStripH, StripCardMetrics.SUGGESTIONS_NON_THUMB_H)
        val sugCardW = StripCardMetrics.cardW(sugThumbH)
        assertTrue(sugThumbH + StripCardMetrics.SUGGESTIONS_NON_THUMB_H <= sugStripH, "$where: suggestion card clipped")
        assertFullWidth169(StripCardMetrics.thumbW(sugThumbH, sugCardW), sugThumbH, sugCardW, "$where suggestions")

        // Up next tab: no search row
        val queueStripH = panel.h - StripCardMetrics.QUEUE_PANEL_CHROME_H
        val queueThumbH = StripCardMetrics.thumbH(queueStripH, StripCardMetrics.QUEUE_NON_THUMB_H)
        val queueCardW = StripCardMetrics.cardW(queueThumbH)
        assertFullWidth169(StripCardMetrics.thumbW(queueThumbH, queueCardW), queueThumbH, queueCardW, "$where up next")
    }

    private fun assertNoOverlap(l: MenuLayout, screenW: Int, screenH: Int, where: String) {
        val rects = listOfNotNull(l.preview, l.settings, l.suggestions)
        for (r in rects) {
            assertTrue(r.x >= 0 && r.y >= 0 && r.right <= screenW && r.bottom <= screenH, "$where: $r off screen")
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]
            val b = rects[j]
            val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
            assertFalse(overlap, "$where: $a overlaps $b")
        }
        assertTrue(l.settings.h >= settingsNeededH, "$where: settings ${l.settings.h} < $settingsNeededH")
    }

    @Test
    fun minimumCanvasKeepsFullWidthThumbnails() {
        for ((w, h) in listOf(640 to 410, 746 to 410, 853 to 410)) {
            val l = MenuLayout.compute(w, h, lineHeight)
            assertFalse(l.suggestionsVertical)
            assertNoOverlap(l, w, h, "${w}x$h")
            assertHorizontalCards(assertNotNull(l.suggestions, "${w}x$h has no suggestions"), "${w}x$h")
        }
    }

    @Test
    fun mediumCanvasKeepsFullWidthThumbnails() {
        for ((w, h) in listOf(800 to 450, 853 to 480, 960 to 470, 1280 to 470)) {
            val l = MenuLayout.compute(w, h, lineHeight)
            assertFalse(l.suggestionsVertical)
            assertNoOverlap(l, w, h, "${w}x$h")
            assertHorizontalCards(assertNotNull(l.suggestions), "${w}x$h")
        }
    }

    @Test
    fun wideCanvasSideColumnThumbnailsFillTheCard() {
        for ((w, h) in listOf(960 to 540, 1280 to 720, 1920 to 1080)) {
            val l = MenuLayout.compute(w, h, lineHeight)
            assertTrue(l.suggestionsVertical, "${w}x$h should be the wide layout")
            assertNoOverlap(l, w, h, "${w}x$h")
            val col = assertNotNull(l.suggestions)
            val cardW = maxOf(StripCardMetrics.CARD_W, col.w - 20)
            assertFullWidth169(cardW, StripCardMetrics.verticalThumbH(cardW), cardW, "${w}x$h")
        }
    }
}
