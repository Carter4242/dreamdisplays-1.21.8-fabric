package com.dreamdisplays.platform.client.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRowLayoutTest {
    @Test
    fun `box and sort button fill the row edge to edge`() {
        for ((x, w) in listOf(0 to 200, 37 to 320, 120 to 611)) {
            val row = searchRowLayout(x, 50, w)
            assertEquals(x + 10, row.box.x, "box starts at the left inset")
            assertEquals(row.box.right + SuggestionsPanel.ACTION_GAP, row.sort.x, "sort follows the box")
            assertEquals(x + w - 10, row.sort.right, "sort ends at the right inset")
            assertEquals(w - 20 - SuggestionsPanel.ACTION_W - SuggestionsPanel.ACTION_GAP, row.box.w, "box takes the rest")
            assertEquals(SuggestionsPanel.ACTION_W, row.sort.w)
            for (r in listOf(row.box, row.sort)) {
                assertEquals(50, r.y)
                assertEquals(SuggestionsPanel.SEARCH_H, r.h)
            }
        }
    }
}
