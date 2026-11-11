package com.dreamdisplays.platform.client.render

/** Pixel size of a display's video texture, shared by the GPU allocation and the decoder target. */
internal object TextureSizing {
    /**
     * Longest side a display texture may have. Every GPU Minecraft runs on supports 8192; past the GL limit the
     * allocation fails, and the extra width of a very wide display is only letterbox padding anyway.
     */
    const val MAX_SIDE = 8192

    /**
     * Even pixel dimensions for a [blockWidth] x [blockHeight] screen at [qualityHeight] pixels tall, scaled down
     * (keeping the block aspect) so neither side exceeds [maxSide].
     */
    fun dimensions(blockWidth: Int, blockHeight: Int, qualityHeight: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val aspect = blockWidth / blockHeight.coerceAtLeast(1).toDouble()
        var w = aspect * qualityHeight
        var h = qualityHeight.toDouble()
        val longest = maxOf(w, h)
        if (longest > maxSide) {
            val scale = maxSide / longest
            w *= scale
            h *= scale
        }
        return even(w.toInt()) to even(h.toInt())
    }

    private fun even(v: Int): Int = (v and 1.inv()).coerceAtLeast(2)
}
