package com.dreamdisplays.platform.client.ui.menu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareDropdownSizingTest {
    @Test
    fun `the picker is wide enough for its longest label, scrollbar or not`() {
        // "Whole PC (game and voice chat too)" is about 180 px in the default font
        for (label in listOf(60, 180, 240, 290)) {
            val w = ShareDropdownSizing.widthFor(label)
            assertTrue(ShareDropdownSizing.labelRoom(w, paged = false) >= label, "$label unpaged")
            assertTrue(ShareDropdownSizing.labelRoom(w, paged = true) >= label, "$label paged")
        }
        assertEquals(ShareDropdownSizing.MIN_WIDTH, ShareDropdownSizing.widthFor(10))
        assertEquals(ShareDropdownSizing.MAX_WIDTH, ShareDropdownSizing.widthFor(1000))
    }

    @Test
    fun `the picker stays on screen, centered on its button where it fits`() {
        // Room on both sides: centered
        assertEquals(400 - 100, ShareDropdownSizing.placeX(anchorCenterX = 400, w = 200, screenW = 800, margin = 4))
        // A button near the right edge (the share button sits at the panel's bottom right): pulled in
        assertEquals(800 - 4 - 200, ShareDropdownSizing.placeX(anchorCenterX = 760, w = 200, screenW = 800, margin = 4))
        // Near the left edge
        assertEquals(4, ShareDropdownSizing.placeX(anchorCenterX = 30, w = 200, screenW = 800, margin = 4))
        // A screen narrower than the popup: left aligned at the margin
        assertEquals(4, ShareDropdownSizing.placeX(anchorCenterX = 100, w = 200, screenW = 150, margin = 4))
    }
}
