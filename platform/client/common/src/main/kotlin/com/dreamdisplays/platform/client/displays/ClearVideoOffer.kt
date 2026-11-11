package com.dreamdisplays.platform.client.displays

/** When the display menu offers its "Clear the video" control. */
internal object ClearVideoOffer {
    /**
     * True when the server clears videos ([supported], see [ClearVideo]), the viewer may set the video ([canSetVideo])
     * and the display has one ([videoUrl]). Playing, still loading or failed alike: a video that never starts is when
     * clearing helps most.
     */
    fun offered(supported: Boolean, canSetVideo: Boolean, videoUrl: String?): Boolean =
        supported && canSetVideo && !videoUrl.isNullOrEmpty()
}
