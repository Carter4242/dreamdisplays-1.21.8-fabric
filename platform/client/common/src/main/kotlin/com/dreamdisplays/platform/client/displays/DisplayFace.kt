package com.dreamdisplays.platform.client.displays

/** What a display's screen shows in the world. */
internal enum class DisplayFace {
    /** The video's picture. */
    PLAYING,

    /** No video set (new or cleared display): the loading look, frozen (see PlaceholderLook). */
    IDLE,

    /** A video is set but not on screen yet: the animated loading bar. */
    LOADING,

    /** The video failed: the error look. */
    ERROR;

    companion object {
        /**
         * [pictureReady]: a frame is up and drawable. A picture already up always shows; otherwise a display without a
         * video ([videoUrl] null or empty) is idle, never loading or failed, whatever its last player left behind.
         */
        fun of(videoUrl: String?, pictureReady: Boolean, errored: Boolean): DisplayFace = when {
            pictureReady -> PLAYING
            videoUrl.isNullOrEmpty() -> IDLE
            errored -> ERROR
            else -> LOADING
        }
    }
}
