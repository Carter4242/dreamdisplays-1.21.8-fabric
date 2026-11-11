package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.stream.MediaStream

/**
 * Decisions for videos with an alpha channel. Such a video skips the native / GPU-YUV paths (they all drop alpha) and
 * is decoded by an `FFmpeg` process into straight-alpha RGBA frames read by the JVM pipe.
 */
object AlphaVideo {
    /**
     * Tallest RGBA frame an alpha video is decoded to. Raw RGBA through a pipe costs 4 bytes a pixel (720p30 is about
     * 110 MB/s, 1080p30 about 250 MB/s), so alpha video is capped here instead of following the display's quality.
     */
    const val MAX_HEIGHT = 720

    /** How one alpha video is decoded; [decoder] is the forced FFmpeg decoder, or null for FFmpeg's default. */
    data class Plan(val decoder: String?)

    /**
     * Returns the decode plan for [stream], or null to play it on the ordinary opaque path: when [allowed] is false,
     * the stream has no alpha, or the decoder that keeps its alpha is missing from this `FFmpeg` ([hasDecoder]).
     */
    fun plan(stream: MediaStream, allowed: Boolean, hasDecoder: (String) -> Boolean): Plan? {
        if (!allowed || !stream.hasAlpha) return null
        val decoder = alphaDecoder(stream.codec) ?: return null
        return if (hasDecoder(decoder)) Plan(decoder) else null
    }

    /**
     * The decoder that keeps the alpha of [codec]. WebM alpha is a VP8 / VP9 side channel that only libvpx reads;
     * FFmpeg's built-in decoders silently drop it. Null for any other codec.
     */
    fun alphaDecoder(codec: String?): String? = when (codec?.lowercase()) {
        "vp9" -> "libvpx-vp9"
        "vp8" -> "libvpx"
        else -> null
    }

    /**
     * Caps [w] x [h] to [MAX_HEIGHT], keeping the aspect ratio and even dimensions. Idempotent, so the decoder and the
     * display texture, which each apply it to the same uncapped size, always agree.
     */
    fun capDims(w: Int, h: Int): Pair<Int, Int> {
        if (h <= MAX_HEIGHT || w <= 0 || h <= 0) return w to h
        val width = ((w.toLong() * MAX_HEIGHT / h).toInt() and 1.inv()).coerceAtLeast(2)
        return width to MAX_HEIGHT
    }
}
