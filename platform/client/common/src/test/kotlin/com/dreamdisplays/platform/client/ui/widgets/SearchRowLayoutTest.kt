package com.dreamdisplays.platform.client.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRowLayoutTest {
    @Test
    fun `box, clear and sort fill the row edge to edge with even gaps`() {
        for ((x, w) in listOf(0 to 200, 37 to 320, 120 to 611)) {
            val row = searchRowLayout(x, 50, w)
            val gap = SuggestionsPanel.ACTION_GAP
            assertEquals(x + 10, row.box.x, "box starts at the left inset")
            assertEquals(row.box.right + gap, row.clear.x, "clear follows the box")
            assertEquals(row.clear.right + gap, row.sort.x, "sort follows clear")
            assertEquals(x + w - 10, row.sort.right, "sort ends at the right inset")
            listOf(row.box, row.clear, row.sort).forEach {
                assertEquals(50, it.y)
                assertEquals(SuggestionsPanel.SEARCH_H, it.h)
            }
            assertEquals(SuggestionsPanel.ACTION_W, row.clear.w)
            assertEquals(SuggestionsPanel.ACTION_W, row.sort.w)
        }
    }

    @Test
    fun `the box gets all the width the two buttons leave`() {
        val row = searchRowLayout(0, 0, 300)
        val buttons = 2 * (SuggestionsPanel.ACTION_W + SuggestionsPanel.ACTION_GAP)
        assertEquals(300 - 20 - buttons, row.box.w)
        assertTrue(row.box.w > 0)
    }
}
