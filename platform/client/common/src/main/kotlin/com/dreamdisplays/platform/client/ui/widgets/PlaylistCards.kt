package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.source.YouTubeUrls
import com.dreamdisplays.media.source.youtube.YouTubePlaylists

/**
 * The cards a YouTube playlist link pasted into the search box shows. A `playlist?list=Y` link shows a playlist card;
 * a `watch?v=X&list=Y` link shows X's own card and the playlist card (from X on), so playing X and queueing the
 * playlist are both a click away. Queueing a playlist card lists the playlist then; the card itself only carries its link.
 */
internal object PlaylistCards {
    enum class Kind { VIDEO, PLAYLIST }

    /** A pasted playlist [link] and the [kinds] of card it shows, in order. */
    data class Plan(val link: YouTubePlaylists.Link, val kinds: List<Kind>)

    /** What [query] shows when it is a playlist link; null for anything else (searched or resolved as before). */
    fun planFor(query: String): Plan? {
        val link = YouTubePlaylists.parseLink(query) ?: return null
        return Plan(link, listOfNotNull(link.startVideoId?.let { Kind.VIDEO }, Kind.PLAYLIST))
    }

    /**
     * The playlist card for [link]: the listing's name (else [fallbackTitle]), its size through [countText] when
     * known, and the start (else first) video's thumbnail.
     */
    fun card(
        link: YouTubePlaylists.Link,
        summary: YouTubePlaylists.Summary?,
        fallbackTitle: String,
        countText: (Long) -> String,
    ): MediaSearchResult {
        val thumbVideo = link.startVideoId ?: summary?.firstVideoId
        return MediaSearchResult(
            id = "playlist:${link.listId}:${link.startVideoId.orEmpty()}",
            title = summary?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            uploader = summary?.videoCount?.let(countText),
            durationSec = null,
            viewCount = null,
            watchUrlOverride = link.url,
            thumbnailUrlOverride = thumbVideo?.let { YouTubeUrls.thumbnailUrl(it) },
            isPlaylist = true,
        )
    }

    /** The playlist link a playlist [card] stands for, or null for any other card. */
    fun linkOf(card: MediaSearchResult): YouTubePlaylists.Link? =
        if (card.isPlaylist) YouTubePlaylists.parseLink(card.getWatchUrl()) else null
}
