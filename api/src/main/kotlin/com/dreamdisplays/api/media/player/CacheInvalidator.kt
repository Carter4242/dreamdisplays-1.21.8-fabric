package com.dreamdisplays.api.media.player

/**
 * Purges any cached resolution for a media URL so the next resolve hits the network fresh.
 *
 * @since 1.8.x
 */
fun interface CacheInvalidator {
    fun invalidate(url: String)

    /**
     * The media host refused (HTTP 403) the stream URLs resolved for [url]. Purges them like [invalidate] and lets
     * the resolvers remember the refusal, so a repeated one can be answered from a different source.
     *
     * @since 1.10.x
     */
    fun streamRefused(url: String) = invalidate(url)
}
