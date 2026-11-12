package com.dreamdisplays.core.menu

/**
 * Fits a row of tabs into a header of a given width: full labels when they fit, else short labels, else icons with
 * their count, else bare icons. A tab never wraps, overlaps another or runs past the header; the chosen form is the
 * same for every tab, so the row reads as one.
 */
object TabStrip {
    /** One tab: its [full] and [short] labels and, for the icon forms, a [count] shown next to its icon (or null). */
    data class Tab(val full: String, val short: String, val count: String? = null)

    enum class Form { FULL, SHORT, ICON, ICON_BARE }

    /** The chosen [form], each tab's [label] (null: icon only, [count] after it when set) and x offset / width. */
    data class Layout(val form: Form, val items: List<Item>) {
        val width: Int get() = items.lastOrNull()?.let { it.x + it.w } ?: 0
    }

    data class Item(val x: Int, val w: Int, val label: String?, val count: String?)

    /** Inner padding of a text tab (both sides together). */
    const val TEXT_PAD = 4

    /**
     * Lays [tabs] out in [available] pixels, [gap] apart (text forms) or [iconGap] apart (icon forms); [iconW] is an
     * icon's width and [measure] a string's width in the menu font.
     */
    fun layout(tabs: List<Tab>, available: Int, gap: Int, iconGap: Int, iconW: Int, measure: (String) -> Int): Layout {
        fun build(form: Form): Layout {
            var x = 0
            val items = tabs.mapIndexed { i, tab ->
                if (i > 0) x += if (form == Form.FULL || form == Form.SHORT) gap else iconGap
                val item = when (form) {
                    Form.FULL -> Item(x, measure(tab.full) + TEXT_PAD, tab.full, null)
                    Form.SHORT -> Item(x, measure(tab.short) + TEXT_PAD, tab.short, null)
                    Form.ICON -> Item(x, iconW + TEXT_PAD + (tab.count?.let { measure(it) + 2 } ?: 0), null, tab.count)
                    Form.ICON_BARE -> Item(x, iconW + TEXT_PAD, null, null)
                }
                x += item.w
                item
            }
            return Layout(form, items)
        }
        for (form in Form.entries) {
            val layout = build(form)
            if (layout.width <= available) return layout
        }
        return build(Form.ICON_BARE)
    }
}
