package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import java.util.*
import kotlin.math.roundToLong

/** One queued video. Everything but [url] / [lang] / [addedBy] is display text supplied by the adding client. */
data class QueueItem(
    val itemId: String,
    val url: String,
    val lang: String,
    val title: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val addedBy: UUID,
    val addedByName: String,
    val addedAtMs: Long,
) {
    /** The wire form sent in a `QueueState`. */
    fun toWire(): QueueItemWire = QueueItemWire(itemId, url, lang, title, durationMs, thumbnailUrl, addedBy, addedByName)
}

/** Server-wide caps; 0 or less means no cap. */
data class QueueLimits(val maxItems: Int, val maxPerPlayer: Int)

/** Outcome of [DisplayQueue.add]. */
enum class QueueAddResult { ADDED, FULL, PLAYER_LIMIT, DUPLICATE }

/**
 * The "Up next" list of one display plus the entry now playing. Not thread-safe: the owner serialises access.
 *
 * [current] is the queue entry the display is playing because the queue advanced to it; a hand-set video clears it
 * ([onVideoSetByHand]). With [loop] on, [advance] puts [current] back at the end before taking the next one, so the
 * list cycles.
 */
class DisplayQueue(
    items: List<QueueItem> = emptyList(),
    loop: Boolean = false,
    current: QueueItem? = null,
) {
    private val list = ArrayList(items)

    /** Re-queue the finished entry at the end instead of dropping it. */
    var loop: Boolean = loop
        private set

    /** The queue entry now playing, if the display got its video from the queue. */
    var current: QueueItem? = current
        private set

    /** Bumped on every change. */
    var revision: Long = 0L
        private set

    /** The queued entries, in play order. */
    val items: List<QueueItem> get() = list.toList()

    val size: Int get() = list.size

    fun isEmpty(): Boolean = list.isEmpty()

    /**
     * True when the end of the playing video should advance: something is queued. With nothing queued the display just
     * loops its video, which is also what a [loop]ing queue whose only entry is playing would do.
     */
    fun hasNext(): Boolean = list.isNotEmpty()

    /** The entry that plays next, or null. */
    fun head(): QueueItem? = list.firstOrNull()

    fun find(itemId: String): QueueItem? = list.firstOrNull { it.itemId == itemId }

    fun indexOf(itemId: String): Int = list.indexOfFirst { it.itemId == itemId }

    /** How many entries [playerId] has waiting. */
    fun countBy(playerId: UUID): Int = list.count { it.addedBy == playerId }

    /**
     * Appends [item] unless the queue is at [QueueLimits.maxItems] or [item]'s adder already has
     * [QueueLimits.maxPerPlayer] entries waiting ([exemptFromPlayerLimit] skips the latter, e.g. for the owner).
     */
    fun add(item: QueueItem, limits: QueueLimits, exemptFromPlayerLimit: Boolean = false): QueueAddResult {
        if (list.any { it.itemId == item.itemId }) return QueueAddResult.DUPLICATE
        if (limits.maxItems > 0 && list.size >= limits.maxItems) return QueueAddResult.FULL
        if (!exemptFromPlayerLimit && limits.maxPerPlayer > 0 && countBy(item.addedBy) >= limits.maxPerPlayer) {
            return QueueAddResult.PLAYER_LIMIT
        }
        list.add(item)
        changed()
        return QueueAddResult.ADDED
    }

    /** Removes the entry [itemId]; returns it, or null when it is not queued. */
    fun remove(itemId: String): QueueItem? {
        val index = indexOf(itemId)
        if (index < 0) return null
        return list.removeAt(index).also { changed() }
    }

    /** Moves [itemId] to [toIndex] (clamped into the list). False when it is not queued or already there. */
    fun move(itemId: String, toIndex: Int): Boolean {
        val from = indexOf(itemId)
        if (from < 0) return false
        val to = toIndex.coerceIn(0, list.size - 1)
        if (to == from) return false
        val item = list.removeAt(from)
        list.add(to, item)
        changed()
        return true
    }

    /** Empties the list (the playing entry stays [current]); returns how many entries were dropped. */
    fun clear(): Int {
        val dropped = list.size
        if (dropped == 0) return 0
        list.clear()
        changed()
        return dropped
    }

    fun setLoop(enabled: Boolean): Boolean {
        if (loop == enabled) return false
        loop = enabled
        changed()
        return true
    }

    /**
     * Takes the next entry and makes it [current]. With [loop] on, the entry that just finished goes back to the end
     * first (so a one-entry loop replays itself). Null when nothing is queued.
     */
    fun advance(): QueueItem? {
        if (loop) current?.let { finished -> if (list.none { it.itemId == finished.itemId }) list.add(finished) }
        if (list.isEmpty()) return null
        val next = list.removeAt(0)
        current = next
        changed()
        return next
    }

    /** The display's video was changed outside the queue: whatever plays now is no queue entry. */
    fun onVideoSetByHand(url: String) {
        if (current == null || current?.url == url) return
        current = null
        changed()
    }

    /**
     * The display plays [item] again after something took it over for a while (a screen share ended): it is the playing
     * entry once more, so a looping queue keeps it. Only when nothing else became current meanwhile. False otherwise.
     */
    fun resumeCurrent(item: QueueItem): Boolean {
        if (current != null) return false
        current = item
        changed()
        return true
    }

    /** An independent copy (same entries, loop flag and current entry). */
    fun copy(): DisplayQueue = DisplayQueue(list, loop, current).also { it.revision = revision }

    private fun changed() {
        revision++
    }
}

/** Display text from a client is untrusted: these keep it short, single-line and free of formatting tricks. */
object QueueText {
    const val MAX_TITLE = 200
    const val MAX_URL = 2048
    const val MAX_THUMBNAIL_URL = 512
    const val MAX_NAME = 32
    const val MAX_ITEM_ID = 64
    const val MAX_DURATION_MS = 24L * 60 * 60 * 1_000

    /** Strips control / bidi-override characters and legacy `§` colour codes, collapses whitespace, caps length. */
    fun cleanTitle(raw: String): String = clean(raw, MAX_TITLE)

    /** Same rules as [cleanTitle] for a player name. */
    fun cleanName(raw: String): String = clean(raw, MAX_NAME)

    /** An `https` URL without whitespace up to [MAX_THUMBNAIL_URL] chars, else empty. */
    fun cleanThumbnail(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty() || url.length > MAX_THUMBNAIL_URL) return ""
        if (!url.startsWith("https://", ignoreCase = true)) return ""
        if (url.any { it.isWhitespace() || it.isISOControl() || it == '"' || it == '<' || it == '>' }) return ""
        return url
    }

    fun cleanDuration(ms: Long): Long = ms.coerceIn(0L, MAX_DURATION_MS)

    private fun clean(raw: String, max: Int): String {
        val sb = StringBuilder(minOf(raw.length, max))
        var skipCode = false
        var lastSpace = true
        for (ch in raw) {
            if (sb.length >= max) break
            if (skipCode) {
                skipCode = false
                continue
            }
            when {
                ch == '§' -> skipCode = true
                ch.isWhitespace() -> if (!lastSpace) {
                    sb.append(' ')
                    lastSpace = true
                }

                ch.isISOControl() || ch in BIDI_CONTROLS -> {}

                else -> {
                    sb.append(ch)
                    lastSpace = false
                }
            }
        }
        return sb.toString().trim()
    }

    private val BIDI_CONTROLS: Set<Char> = setOf(
        '؜', '‎', '‏', '‪', '‫', '‬', '‭', '‮',
        '⁦', '⁧', '⁨', '⁩',
    )
}

/** When a playing timeline reaches the end of its video. */
object QueueTiming {
    /**
     * Whether adding to [displayId]'s queue should start the new entry at once: only when the display has no video
     * at all ([displayUrl] blank). A display that plays something keeps playing it; the entry waits for the end
     * ([advanceDue]) or a skip.
     */
    fun startsOnAdd(displayUrl: String): Boolean = displayUrl.isBlank()

    /**
     * Milliseconds until the current play-through of [timeline] ends at [nowMs], or null when that is unknown
     * (paused, or no duration reported yet). A looping timeline that already wrapped counts down to its next wrap.
     */
    fun msUntilEnd(timeline: Timeline, nowMs: Long): Long? {
        if (timeline.paused || timeline.durationMs <= 0L) return null
        val raw = rawPosition(timeline, nowMs)
        val within = if (timeline.loop) raw % timeline.durationMs else raw.coerceAtMost(timeline.durationMs)
        return timeline.durationMs - within
    }

    /**
     * True when the display should move on to the next entry: within [earlyMs] of wall time before the end of the
     * current play-through, or once the timeline ran past an end since its anchor (a looping timeline wraps silently,
     * and the server may tick well after the end, e.g. after a seek into the last second). The latest end only counts
     * when it happened at or after [armedAtMs] (when the queue got the entry it would advance to): a play-through that
     * ended before anything was waiting is no end to advance at, so an add right after a loop wrap waits for the next
     * end. [durationMs] overrides the timeline's own when it has none (the playing queue entry's duration); with no
     * duration at all (not reported yet, live stream) it is never due.
     */
    fun advanceDue(
        timeline: Timeline, nowMs: Long, earlyMs: Long, armedAtMs: Long = Long.MIN_VALUE,
        durationMs: Long = timeline.durationMs,
    ): Boolean {
        if (timeline.paused || durationMs <= 0L) return false
        val rate = timeline.rate.coerceAtLeast(PlaybackSpeed.MIN)
        val raw = rawPosition(timeline, nowMs)
        val within = if (timeline.loop) raw % durationMs else raw.coerceAtMost(durationMs)
        if ((durationMs - within) / rate <= earlyMs) return true
        if (raw < durationMs) return false
        val sinceEnd = if (timeline.loop) within else raw - durationMs
        return nowMs - (sinceEnd / rate).roundToLong() >= armedAtMs
    }

    /**
     * Wall time at which the play-through of [timeline] that is ending ends: its anchor plus the content left divided
     * by the speed, when that is at most [earlyMs] after [nowMs]. [nowMs] when that end is already past (a late tick
     * just after a loop wrap counts as past). Null when unknown (paused, no duration). The next queue entry is
     * scheduled to start at exactly this instant, so preloaded clients cut from the last frame straight to it.
     */
    fun switchAtMs(timeline: Timeline, nowMs: Long, earlyMs: Long, durationMs: Long = timeline.durationMs): Long? {
        if (timeline.paused || durationMs <= 0L) return null
        val rate = timeline.rate.coerceAtLeast(PlaybackSpeed.MIN)
        val raw = rawPosition(timeline, nowMs)
        val endPosition = if (timeline.loop) (raw / durationMs + 1) * durationMs else durationMs
        val endAt = timeline.serverTimeMs + ((endPosition - timeline.positionMs.coerceAtLeast(0L)) / rate).roundToLong()
        return if (endAt <= nowMs || endAt - nowMs > earlyMs) nowMs else endAt
    }

    /**
     * Wall time at which a playing [timeline] with nothing queued should restart its video from 0 for everyone, or null
     * when that is not yet due (more than [earlyMs] of wall time before the end), it is paused, or no duration is known.
     *
     * [durationMs] is the video's known (scheduled) length. A timeline that wraps by itself (looping, with that
     * duration) restarts at the end of its current play-through. One that does not ([Timeline.durationMs] unknown or
     * not looping) and already ran past [durationMs] restarts at [nowMs]: its clock kept counting past an end that
     * nobody wrapped. Only an end ahead counts for a wrapping timeline, so a late tick never restarts it mid-video.
     */
    fun loopRestartAtMs(timeline: Timeline, nowMs: Long, earlyMs: Long, durationMs: Long = timeline.durationMs): Long? {
        if (timeline.paused || durationMs <= 0L) return null
        val rate = timeline.rate.coerceAtLeast(PlaybackSpeed.MIN)
        val raw = rawPosition(timeline, nowMs)
        val wraps = timeline.loop && timeline.durationMs == durationMs
        val left = if (wraps) durationMs - raw % durationMs else durationMs - raw
        if (left <= 0L) return nowMs
        val leftWallMs = (left / rate).roundToLong()
        if (leftWallMs > earlyMs) return null
        return maxOf(nowMs, timeline.serverTimeMs) + leftWallMs
    }

    /**
     * The wall time at which a loop restart [scheduled] by [loopRestartAtMs] starts the video over, while it is still
     * the display's [current] timeline and that instant is still ahead of [nowMs]; null otherwise. An entry queued in
     * that window lands before the end the restart stands in for, so the queue moves on there instead of after one more
     * play-through.
     */
    fun pendingRestartAtMs(scheduled: Timeline?, current: Timeline?, nowMs: Long): Long? {
        if (scheduled == null || current != scheduled) return null
        return scheduled.serverTimeMs.takeIf { nowMs < it }
    }

    /** Past the media end the timeline waits this long more: switching late holds the last frame, early cuts the video. */
    const val END_SAFETY_MS = 150L

    /** How far a client's duration report may lie outside the playing queue entry's (metadata) duration. */
    const val MAX_DURATION_RAISE_MS = 3_000L

    /**
     * Timeline length to schedule the end of a video whose media duration is reported as [durationMs] (0: unknown).
     * Whole seconds (YouTube metadata, older clients) are floored, so the media may run up to a second longer; add
     * that, plus [END_SAFETY_MS].
     */
    fun scheduledDurationMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val slack = if (durationMs % 1_000L == 0L) 1_000L else 0L
        return durationMs + slack + END_SAFETY_MS
    }

    /**
     * The media duration (ms) to run a display's current video for, 0 when nothing is known. [entryMs]: the playing
     * queue entry's duration (0 when none); [reportsMs]: the latest duration each viewer's client measured for this
     * video; [floorMs]: what was settled on before, which is never undercut.
     *
     * The largest report wins, so one short report (a client still on the previous video, a stream that only knew
     * part of its length) never ends the video early for everyone. With a queue entry, the entry's duration is the
     * least the video runs, and reports more than [MAX_DURATION_RAISE_MS] beyond it (plus its whole-second rounding)
     * are another video's and ignored.
     */
    fun mediaDurationMs(entryMs: Long, reportsMs: Collection<Long>, floorMs: Long = 0L): Long {
        val best = if (entryMs > 0L) {
            val slack = if (entryMs % 1_000L == 0L) 1_000L else 0L
            val most = entryMs + slack + MAX_DURATION_RAISE_MS
            maxOf(entryMs, reportsMs.filter { it <= most }.maxOrNull() ?: 0L)
        } else {
            reportsMs.maxOrNull() ?: 0L
        }
        return maxOf(best, floorMs, 0L)
    }

    /**
     * The media duration observed ends settle on, or null when they don't decide it: every reporter in [present] (the
     * players watching now) has reported where its stream actually ended ([observed]), and the largest of those ends
     * wins. Null when nobody present reported, or one of them only knows a header length.
     */
    fun <K> observedMediaMs(reportsMs: Map<K, Long>, observed: Set<K>, present: Collection<K>): Long? {
        val reporters = present.filter { it in reportsMs }
        if (reporters.isEmpty() || reporters.any { it !in observed }) return null
        return reporters.maxOf { reportsMs.getValue(it) }.takeIf { it > 0L }
    }

    /** Content ms before (or after a loop wrap past) the end within which a client's seek back to the start is its own end-of-stream loop. */
    const val END_OF_STREAM_WINDOW_MS = 2_000L

    /** A seek to at most this many ms is a restart from the beginning. */
    const val RESTART_TARGET_MAX_MS = 1_000L

    /**
     * True when a client's seek to [targetMs] reaching a playing [timeline] at [nowMs] is that client's player looping
     * at its own end of stream (older clients do that), not a viewer's seek: a jump back to the start while the shared
     * timeline is within [END_OF_STREAM_WINDOW_MS] of the end, past it, or just wrapped.
     */
    fun isEndOfStreamLoop(timeline: Timeline, nowMs: Long, targetMs: Long, durationMs: Long = timeline.durationMs): Boolean {
        if (timeline.paused || durationMs <= 0L || targetMs > RESTART_TARGET_MAX_MS) return false
        val raw = rawPosition(timeline, nowMs)
        if (raw >= durationMs && !timeline.loop) return true
        val within = raw % durationMs
        return within >= durationMs - END_OF_STREAM_WINDOW_MS || (raw >= durationMs && within <= END_OF_STREAM_WINDOW_MS)
    }

    /** Content position at [nowMs], unwrapped: advances at the timeline's speed, holds before a scheduled start. */
    private fun rawPosition(timeline: Timeline, nowMs: Long): Long {
        if (nowMs <= timeline.serverTimeMs) return timeline.positionMs.coerceAtLeast(0L)
        val elapsed = nowMs - timeline.serverTimeMs
        val advanced = if (timeline.rate == PlaybackSpeed.NORMAL) elapsed else (elapsed * timeline.rate).roundToLong()
        return (timeline.positionMs + advanced).coerceAtLeast(0L)
    }
}
