package com.dreamdisplays.platform.client.displays

/** Whether a saved resume position applies to the video a display loads. */
internal object ResumePosition {
    /**
     * True when the position was saved in [savedUrl] and the display now loads another video [loadingUrl]. An unknown
     * [savedUrl] (settings from before it was kept) keeps the old behaviour: the position is used.
     */
    fun belongsToOtherVideo(savedUrl: String?, loadingUrl: String): Boolean = savedUrl != null && savedUrl != loadingUrl
}
