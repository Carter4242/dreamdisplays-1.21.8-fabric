package com.dreamdisplays.platform.client.ui

/** Size of a PiP overlay inside the GUI-scaled viewport. */
internal object PipSizing {
    const val MIN_WIDTH = 80
    const val MIN_HEIGHT = 45

    /**
     * The PiP's width x height for a [sw] x [sh] viewport: [sizeFraction] of the viewport width at the video's
     * [aspect], shrunk to keep the whole PiP (and so its controls) within the viewport height less [margin] on both
     * sides — a portrait video would otherwise be taller than the screen.
     */
    fun fit(sw: Int, sh: Int, sizeFraction: Float, aspect: Double, margin: Int): Pair<Int, Int> {
        val safeAspect = if (aspect > 0.0 && aspect.isFinite()) aspect else 16.0 / 9.0
        var w = (sw * sizeFraction).toInt().coerceAtLeast(MIN_WIDTH)
        var h = (w / safeAspect).toInt().coerceAtLeast(MIN_HEIGHT)
        val maxH = (sh - 2 * margin).coerceAtLeast(MIN_HEIGHT)
        if (h > maxH) {
            h = maxH
            w = (h * safeAspect).toInt().coerceAtLeast(1)
        }
        return w to h
    }
}
