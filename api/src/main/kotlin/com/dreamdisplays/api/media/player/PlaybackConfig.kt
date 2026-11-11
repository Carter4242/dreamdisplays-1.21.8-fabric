package com.dreamdisplays.api.media.player

/**
 * Read-only playback-relevant configuration the media player needs from the host application.
 *
 * @since 1.8.x
 */
interface PlaybackConfig {
    /** Default per-display volume (0–2.0) applied to a freshly created player. */
    val defaultDisplayVolume: Double

    /** Whether hardware-accelerated decoding should be attempted. */
    val useHwAccel: Boolean

    /** Whether the current user may use premium quality tiers (e.g. >1080p). */
    val isPremium: Boolean

    /** Whether the GPU-side YUV (planar I420) render path is active; selects the planar decode output. */
    val gpuYuvActive: Boolean

    /**
     * Whether a video with an alpha channel may be decoded and drawn with transparency. When false such a video plays
     * on the ordinary opaque path.
     *
     * @since 1.10.x
     */
    val transparentVideoAllowed: Boolean get() = true

    /**
     * Whether decoding may go through the native frame pipe (the mod's native library). When false every video runs as
     * an `FFmpeg` process into the plain JVM pipe (RGBA). Read whenever a video channel is created.
     *
     * @since 1.10.x
     */
    val nativePipeAllowed: Boolean get() = true

    /**
     * Whether in-process decoding (libav inside the game) may be used; when false the native pipe is fed by an `FFmpeg`
     * process. Read whenever a video channel launches.
     *
     * @since 1.10.x
     */
    val inProcessDecodeAllowed: Boolean get() = true
}
