package com.dreamdisplays.platform.client.ui

/** Picks the monitor a window is on, from the monitors' desktop rectangles. */
internal object MonitorPick {
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Index of the monitor containing the point ([cx], [cy]) (the window's centre); when none does (a window dragged
     * half off the desktop), the monitor whose centre is nearest. -1 when there are no usable monitors.
     */
    fun indexFor(cx: Int, cy: Int, monitors: List<Rect>): Int {
        val usable = monitors.withIndex().filter { it.value.width > 0 && it.value.height > 0 }
        usable.firstOrNull { (_, r) -> cx >= r.x && cx < r.x + r.width && cy >= r.y && cy < r.y + r.height }
            ?.let { return it.index }
        return usable.minByOrNull { (_, r) ->
            val dx = (r.x + r.width / 2L) - cx
            val dy = (r.y + r.height / 2L) - cy
            dx * dx + dy * dy
        }?.index ?: -1
    }
}
