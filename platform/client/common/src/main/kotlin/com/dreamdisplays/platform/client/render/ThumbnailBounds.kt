package com.dreamdisplays.platform.client.render

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Keeps a thumbnail from costing more memory than a thumbnail should. Decoding allocates several full-size pixel
 * buffers (the decoded image, its ARGB copy, the texture), and a small compressed file can declare an enormous size,
 * so the size is read from the image header before anything is decoded.
 */
internal object ThumbnailBounds {
    /** 3840 x 2160; real thumbnails and covers are far smaller. */
    const val MAX_PIXELS: Long = 3840L * 2160L

    /** The width and height [bytes] declare, read from the header only; null when no reader knows the format. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use {
            val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(it, true, true)
                return reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }

    /** Throws when [bytes] declare more than [MAX_PIXELS]; an unknown format is left to the decoder to refuse. */
    @Throws(IOException::class)
    fun check(bytes: ByteArray) {
        val (w, h) = dimensions(bytes) ?: return
        if (w <= 0 || h <= 0 || w.toLong() * h.toLong() > MAX_PIXELS) {
            throw IOException("Thumbnail of ${w}x$h is over the $MAX_PIXELS-pixel limit.")
        }
    }
}
