package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.api.media.source.YouTubeUrls
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import com.dreamdisplays.media.source.youtube.YouTubeInnerTube
import com.dreamdisplays.media.source.youtube.cache.VideoMetadataCache
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.launch

/**
 * Lengths for queue cards whose entry came without one: older clients queue a pasted YouTube link with length 0. The
 * card looks the length up itself, once per video id per game session, a couple at a time, off the render thread.
 * Display only: nothing goes back to the server.
 */
object QueueLengths {
    private val lookup = OncePerKey<String, Long>(MAX_CONCURRENT, MAX_IDS, { task ->
        DreamCoroutines.clientIo.launch { task() }
    }) { videoId ->
        VideoMetadataCache.get(videoId)?.durationSec?.takeIf { it > 0L } ?: YouTubeInnerTube.lengthSeconds(videoId)
    }

    /** [item]'s length in ms: its own, else a looked-up one for a YouTube video (0 until known, or when it has none). */
    fun durationMs(item: QueueItemWire): Long {
        if (item.durationMs > 0L) return item.durationMs
        val videoId = YouTubeUrls.extractVideoId(item.url) ?: return 0L
        return (lookup.get(videoId) ?: 0L) * 1_000L
    }

    private const val MAX_CONCURRENT = 2
    private const val MAX_IDS = 2_000
}

/**
 * Looks each key up at most once: [get] returns the value once [fetch] found one, null before that and when it found
 * none or failed (never retried). At most [maxConcurrent] fetches run at once, each started through [launch]; the
 * rest wait in order. After [maxKeys] distinct keys new ones are not looked up at all, so memory stays bounded.
 */
internal class OncePerKey<K : Any, V : Any>(
    private val maxConcurrent: Int,
    private val maxKeys: Int,
    private val launch: (task: () -> Unit) -> Unit,
    private val fetch: (K) -> V?,
) {
    private val lock = Any()
    private val values = HashMap<K, V>()
    private val attempted = HashSet<K>()
    private val waiting = ArrayDeque<K>()
    private var running = 0

    fun get(key: K): V? {
        synchronized(lock) {
            values[key]?.let { return it }
            if (attempted.size >= maxKeys || !attempted.add(key)) return null
            waiting.addLast(key)
        }
        pump()
        return null
    }

    private fun pump() {
        while (true) {
            val key = synchronized(lock) {
                if (running >= maxConcurrent) return
                waiting.removeFirstOrNull()?.also { running++ } ?: return
            }
            val started = runCatching { launch { run(key) } }.isSuccess
            if (!started) synchronized(lock) { running-- }
        }
    }

    private fun run(key: K) {
        try {
            val value = runCatching { fetch(key) }.getOrNull()
            if (value != null) synchronized(lock) { values[key] = value }
        } finally {
            synchronized(lock) { running-- }
            pump()
        }
    }
}
