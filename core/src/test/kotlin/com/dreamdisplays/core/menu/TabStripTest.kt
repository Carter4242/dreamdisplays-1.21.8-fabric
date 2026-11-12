package com.dreamdisplays.core.menu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabStripTest {
    /** 6 px per character, like the menu font's average. */
    private val measure = { s: String -> s.length * 6 }
    private val tabs = [
        TabStrip.Tab("Suggestions", "Videos"),
        TabStrip.Tab("Up next (3)", "Next (3)", "3"),
        TabStrip.Tab("Activity", "Log"),
        TabStrip.Tab("Viewers (12)", "Viewers 12", "12"),
    ]

    private fun fit(width: Int) = TabStrip.layout(tabs, width, gap = 10, iconGap = 4, iconW = 12, measure = measure)

    @Test
    fun `picks the widest form that fits`() {
        assertEquals(TabStrip.Form.FULL, fit(1_000).form)
        // Full: 66+4 + 66+4 + 48+4 + 72+4 + 3*10 = 298
        assertEquals(298, fit(298).width)
        assertEquals(TabStrip.Form.SHORT, fit(297).form)
        assertEquals(TabStrip.Form.ICON, fit(150).form)
        assertEquals(TabStrip.Form.ICON_BARE, fit(70).form)
        // Nothing fits: bare icons all the same (the menu's minimum size keeps them inside)
        assertEquals(TabStrip.Form.ICON_BARE, fit(10).form)
    }

    @Test
    fun `tabs never overlap and stay inside`() {
        for (width in 64..1_000) {
            val layout = fit(width)
            layout.items.zipWithNext { a, b -> assertTrue(a.x + a.w < b.x, "$width: $a / $b") }
            // Bare icons take 4 * 16 + 3 * 4 = 76
            if (width >= 76) assertTrue(layout.width <= width, "$width: ${layout.width}")
            assertEquals(tabs.size, layout.items.size)
        }
    }

    @Test
    fun `icon forms keep counts only while they fit`() {
        val icon = fit(150)
        assertEquals([null, "3", null, "12"], icon.items.map { it.count })
        assertTrue(icon.items.all { it.label == null })
        assertEquals("Next (3)", fit(260).items[1].label)
    }
}
