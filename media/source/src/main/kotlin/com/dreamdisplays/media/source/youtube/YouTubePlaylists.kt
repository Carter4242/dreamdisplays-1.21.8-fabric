package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.source.YouTubeUrls
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Lists the videos of a YouTube playlist (flat: id, title, duration, uploader; nothing is resolved for playback) with
 * `NewPipeExtractor`, paging until enough entries are collected. Used to queue a whole playlist at once.
 */
object YouTubePlaylists {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val MAX_PAGES = 40

    /** One playable playlist entry. */
    data class Entry(val videoId: String, val title: String, val durationSec: Long, val uploader: String?)

    /** A fetched playlist: the [entries] taken, how many were [unavailable] (private, deleted, members-only). */
    data class Listing(val title: String, val entries: List<Entry>, val unavailable: Int, val more: Boolean)

    /** A playlist link: its list id, and the video a `watch?v=X&list=Y` link points at (to start from), if any. */
    data class Link(val listId: String, val startVideoId: String?)

    /**
     * The playlist [input] links to, or null. `youtube.com/playlist?list=Y` lists the whole playlist; a
     * `watch?v=X&list=Y` (or `youtu.be/X?list=Y`) link lists it from X on, so "queue this link" means "this video and
     * everything after it", the way YouTube itself plays it.
     */
    fun parseLink(input: String): Link? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(if ("://" in raw) raw else "https://$raw") }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val youtube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        if (!youtube) return null
        val params = query(uri.rawQuery)
        val listId = params["list"]?.takeIf { LIST_ID.matches(it) } ?: return null
        return Link(listId, YouTubeUrls.extractVideoId(raw))
    }

    /**
     * Fetches up to [limit] playable entries of [link]'s playlist, starting at its start video when it has one.
     * Blocking (network); call off the client thread.
     */
    fun fetch(link: Link, limit: Int): Listing {
        NewPipeResolver.ensureInitialized()
        val url = "https://www.youtube.com/playlist?list=${link.listId}"
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val collector = Collector(link.startVideoId, limit)
        collector.accept(info.relatedItems)
        var page: Page? = if (info.hasNextPage()) info.nextPage else null
        var pages = 1
        while (page != null && !collector.full && pages < MAX_PAGES) {
            val more = runCatching { PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page) }
                .onFailure { logger.warn("Playlist {}: could not load page {}: {}", link.listId, pages + 1, it.message) }
                .getOrNull() ?: break
            collector.accept(more.items)
            page = if (more.hasNextPage()) more.nextPage else null
            pages++
        }
        return Listing(info.name.orEmpty(), collector.entries, collector.unavailable, collector.full || page != null)
    }

    private class Collector(private val startVideoId: String?, private val limit: Int) {
        private val fromStart = ArrayList<Entry>()
        private val beforeStart = ArrayList<Entry>()
        private var started = startVideoId == null
        private var unavailableFromStart = 0
        private var unavailableBefore = 0

        /** From the start video on; the whole list when the start video is not in it (removed, private). */
        val entries: List<Entry> get() = if (started) fromStart else beforeStart
        val unavailable: Int get() = if (started) unavailableFromStart else unavailableBefore
        val full: Boolean get() = fromStart.size >= limit

        fun accept(items: List<StreamInfoItem>) {
            for (item in items) {
                if (full) return
                val id = YouTubeUrls.extractVideoId(item.url) ?: continue
                if (!started && id == startVideoId) started = true
                val entry = entryOf(id, item)
                if (started) {
                    if (entry == null) unavailableFromStart++ else fromStart += entry
                } else if (beforeStart.size < limit) {
                    if (entry == null) unavailableBefore++ else beforeStart += entry
                }
            }
        }
    }

    private fun entryOf(id: String, item: StreamInfoItem): Entry? {
        val availability = runCatching { item.contentAvailability }.getOrNull()
        val title = item.name.orEmpty()
        if (!isPlayable(availability, title)) return null
        val duration = runCatching { item.duration }.getOrDefault(-1L)
        return Entry(id, title, duration.coerceAtLeast(0L), runCatching { item.uploaderName }.getOrNull())
    }

    /** Private, deleted, members-only, paid and not-yet-premiered entries are skipped. */
    internal fun isPlayable(availability: ContentAvailability?, title: String): Boolean {
        if (availability != null && availability != ContentAvailability.AVAILABLE &&
            availability != ContentAvailability.UNKNOWN
        ) return false
        return title !in UNAVAILABLE_TITLES
    }

    internal fun query(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = part.substring(0, eq)
            val value = runCatching { URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8) }.getOrNull()
            value?.let { key to it }
        }.toMap()
    }

    private val LIST_ID = Regex("^[A-Za-z0-9_-]{2,64}$")
    private val UNAVAILABLE_TITLES = setOf("[Private video]", "[Deleted video]", "[Unavailable video]")
}
