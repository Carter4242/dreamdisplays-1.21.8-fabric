package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.common.packets.QueueState
import com.dreamdisplays.core.queue.DisplayQueue
import com.dreamdisplays.core.queue.QueueAddResult
import com.dreamdisplays.core.queue.QueueItem
import com.dreamdisplays.core.queue.QueueLimits
import com.dreamdisplays.core.queue.QueueText
import com.dreamdisplays.core.queue.QueueTiming
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.storage.DisplayQueueStore
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.shared.playback.FullscreenPrepareRoster
import com.dreamdisplays.shared.playback.FullscreenPrepareState
import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.shared.playback.PlaybackPermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-authoritative "Up next" queue per display. When a `SYNCED` / `BROADCAST` display's timeline reaches the end
 * of its video and something is queued, the display switches to the next entry through the platform's normal video
 * change ([applyVideo]), so every client, old ones included, just sees the video change.
 *
 * Clients get the queue ([QueueState]) and preload its head shortly before the end, reporting progress per player in a
 * [FullscreenPrepareRoster] keyed by the head's item id (the bookkeeping fullscreen preparation uses). Once every
 * client READY on it starts the next video without waiting for it ([TimelineManager.onVideoChanged]'s readiness-gated
 * hold): instantly when every nearby viewer is, otherwise as soon as the rest report it ready. A
 * client without the queue (`ClientHello.supportsQueue` unset) gets no queue packets and just sees the video change.
 * All queue edits run under the queue's own lock.
 */
object QueueManager {
    /** Server-wide knobs, `[queue]` in the config. */
    data class Settings(
        val enabled: Boolean = true,
        val maxItems: Int = 50,
        val maxPerPlayer: Int = 10,
    )

    /** Outcome of a queue edit, for command / chat feedback. */
    enum class Result { OK, DISABLED, NOT_ALLOWED, NOT_RUNNING, FULL, PLAYER_LIMIT, NOT_FOUND, EMPTY, THROTTLED, REFUSED }

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Send the switch to the next entry at least this long (wall time) before the end; it is scheduled to start at the
     * end instant itself, so this only has to cover the slowest viewer's one-way latency plus a [tick] period.
     */
    private const val ADVANCE_EARLY_MS = 300L
    private const val TICK_MARGIN_MS = 150L

    /**
     * With nobody holding the next entry preloaded the new video takes over the moment its URL arrives (no scheduled
     * start), so it is sent only this long before the timeline's end: that end is [QueueTiming.END_SAFETY_MS] past the
     * media's, so the switch lands after the media ended, within a [tick] period of it, and before the timeline wraps.
     */
    private const val UNPRELOADED_EARLY_MS = 50L
    private const val ADD_COOLDOWN_MS = 150L
    private const val EDIT_COOLDOWN_MS = 150L
    private const val PRELOAD_COOLDOWN_MS = 100L
    private const val MAX_SKIPS_PER_ADVANCE = 5

    /** Most entries one batch (playlist) add looks at. */
    const val MAX_BATCH = 100
    private const val PERSIST_DEBOUNCE_MS = 2_000L

    private lateinit var transport: PlaybackTransport

    /** Where the platform's `[queue]` settings come from. */
    var settings: () -> Settings = { Settings() }

    /**
     * Switches [display] to [item] the way a player-set video would (addon veto, save, broadcast, timeline reset with
     * a start held until every viewer is ready, [readyPlayers] counted ready already). Returns false when refused.
     */
    var applyVideo: (display: DisplayData, item: QueueItem, readyPlayers: Collection<UUID>, startAtMs: Long?) -> Boolean =
        { _, _, _, _ -> false }

    /**
     * Called just before [display] switches to queue entry [item] (karaoke: make its singer the active one, so the
     * first timeline of the new video already carries their lead). False skips the entry like a refused one.
     */
    var beforeStart: (display: DisplayData, item: QueueItem) -> Boolean = { _, _ -> true }

    private val queues = ConcurrentHashMap<UUID, DisplayQueue>()
    private val rosters = ConcurrentHashMap<UUID, FullscreenPrepareRoster>()

    /** When each queue last got something to advance to ([DisplayQueue.hasNext]): a loop wrap before that is no end. */
    private val armedAt = ConcurrentHashMap<UUID, Long>()
    private val addThrottle = ActionThrottle()
    private val editThrottle = ActionThrottle()
    private val preloadThrottle = ActionThrottle()
    /** Every save holds it: the debounced one and [flush] write the same temporary file. */
    private val saveLock = Any()
    private val persistScheduled = AtomicBoolean(false)

    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /** Loads the persisted queues; call once at startup, after the displays are registered. */
    fun restore() {
        queues.clear()
        rosters.clear()
        armedAt.clear()
        val loaded = DisplayQueueStore.load()
        var entries = 0
        for ((displayId, queue) in loaded) {
            if (DisplayManager.getDisplayData(displayId) == null) continue
            queues[displayId] = queue
            armedAt[displayId] = transport.nowMs()
            entries += queue.size
        }
        if (entries > 0) logger.info("Restored {} queued video(s) on {} display(s).", entries, queues.size)
    }

    /** Writes every queue now (plugin disable). */
    fun flush() {
        runCatching { synchronized(saveLock) { DisplayQueueStore.save(snapshot()) } }
            .onFailure { logger.warn("Could not save display queues.", it) }
    }

    /** True when [playerId]'s client shows the queue and preloads. */
    fun isCapable(playerId: UUID): Boolean = V2PlayerTracker.helloOf(playerId)?.supportsQueue == true

    private fun limits(): QueueLimits = settings().let { QueueLimits(it.maxItems, it.maxPerPlayer) }

    /** A copy of [displayId]'s queue, or null when it has none. */
    fun snapshotOf(displayId: UUID): DisplayQueue? = queues[displayId]?.let { synchronized(it) { it.copy() } }

    /** A video to queue, as sent by a client (display text not yet cleaned). */
    data class NewEntry(val url: String, val lang: String, val title: String, val durationMs: Long, val thumbnailUrl: String)

    /** Outcome of [addAll]: [result] is OK when at least one entry went in; [added] how many did. */
    data class AddOutcome(val result: Result, val added: Int)

    /** Adds a video to [display]'s queue for [actorId]; [url] and [lang] must already be validated by the caller. */
    fun add(
        display: DisplayData, ctx: PlaybackContext, actorId: UUID, actorName: String,
        url: String, lang: String, title: String, durationMs: Long, thumbnailUrl: String,
        throttled: Boolean = true,
    ): Result = addAll(
        display, ctx, actorId, actorName, listOf(NewEntry(url, lang, title, durationMs, thumbnailUrl)), throttled,
    ).result

    /**
     * Adds [entries] in order (a playlist) until the queue or [actorId]'s share of it is full; URLs must already be
     * validated by the caller. At most [MAX_BATCH] entries are looked at.
     */
    fun addAll(
        display: DisplayData, ctx: PlaybackContext, actorId: UUID, actorName: String,
        entries: List<NewEntry>, throttled: Boolean = true,
    ): AddOutcome {
        if (!settings().enabled) return AddOutcome(Result.DISABLED, 0)
        if (display.virtual || !PlaybackPermissions.queueRuns(ctx.mode)) return AddOutcome(Result.NOT_RUNNING, 0)
        if (!PlaybackPermissions.canAddToQueue(ctx)) return AddOutcome(Result.NOT_ALLOWED, 0)
        val valid = entries.take(MAX_BATCH).filter { it.url.isNotBlank() && it.url.length <= QueueText.MAX_URL }
        if (valid.isEmpty()) return AddOutcome(Result.REFUSED, 0)
        if (throttled && !addThrottle.tryAcquire(actorId, ADD_COOLDOWN_MS)) return AddOutcome(Result.THROTTLED, 0)

        val name = QueueText.cleanName(actorName)
        val now = transport.nowMs()
        val queue = queues.computeIfAbsent(display.id) { DisplayQueue() }
        var added = 0
        var stoppedBy: Result? = null
        synchronized(queue) {
            if (!queue.hasNext()) armedAt[display.id] = now
            for (entry in valid) {
                val item = QueueItem(
                    itemId = newItemId(),
                    url = entry.url,
                    lang = entry.lang,
                    title = QueueText.cleanTitle(entry.title),
                    durationMs = QueueText.cleanDuration(entry.durationMs),
                    thumbnailUrl = QueueText.cleanThumbnail(entry.thumbnailUrl),
                    addedBy = actorId,
                    addedByName = name,
                    addedAtMs = now,
                )
                when (queue.add(item, limits(), exemptFromPlayerLimit = ctx.isOwner || ctx.isAdmin)) {
                    QueueAddResult.ADDED -> added++
                    QueueAddResult.FULL -> stoppedBy = Result.FULL
                    QueueAddResult.PLAYER_LIMIT -> stoppedBy = Result.PLAYER_LIMIT
                    QueueAddResult.DUPLICATE -> {}
                }
                if (stoppedBy != null) break
            }
        }
        if (added == 0) return AddOutcome(stoppedBy ?: Result.REFUSED, 0)
        changed(display)
        // Only a display with no video at all starts the entry now; one that plays something keeps playing it and
        // the entry waits for the end of the video (tick) or a skip
        if (QueueTiming.startsOnAdd(display.url)) advance(display)
        return AddOutcome(Result.OK, added)
    }

    /** Removes entry [itemId]; the adder, owner or admin may. */
    fun remove(display: DisplayData, ctx: PlaybackContext, actorId: UUID, itemId: String): Result {
        val queue = queues[display.id] ?: return Result.NOT_FOUND
        val item = synchronized(queue) { queue.find(itemId) } ?: return Result.NOT_FOUND
        if (!PlaybackPermissions.canRemoveFromQueue(ctx, isAdder = item.addedBy == actorId)) return Result.NOT_ALLOWED
        if (!editThrottle.tryAcquire(actorId, EDIT_COOLDOWN_MS)) return Result.THROTTLED
        if (synchronized(queue) { queue.remove(itemId) } == null) return Result.NOT_FOUND
        changed(display)
        return Result.OK
    }

    /** Moves entry [itemId] to [toIndex]; owner or admin. */
    fun move(display: DisplayData, ctx: PlaybackContext, actorId: UUID, itemId: String, toIndex: Int): Result {
        if (!PlaybackPermissions.canManageQueue(ctx)) return Result.NOT_ALLOWED
        val queue = queues[display.id] ?: return Result.NOT_FOUND
        if (!editThrottle.tryAcquire(actorId, EDIT_COOLDOWN_MS)) return Result.THROTTLED
        if (!synchronized(queue) { queue.move(itemId, toIndex) }) return Result.NOT_FOUND
        changed(display)
        return Result.OK
    }

    /** Empties the queue; owner or admin. */
    fun clear(display: DisplayData, ctx: PlaybackContext): Result {
        if (!PlaybackPermissions.canManageQueue(ctx)) return Result.NOT_ALLOWED
        val queue = queues[display.id] ?: return Result.EMPTY
        if (synchronized(queue) { queue.clear() } == 0) return Result.EMPTY
        changed(display)
        return Result.OK
    }

    /** Turns looping on or off; owner or admin. */
    fun setLoop(display: DisplayData, ctx: PlaybackContext, enabled: Boolean): Result {
        if (!settings().enabled) return Result.DISABLED
        if (!PlaybackPermissions.canManageQueue(ctx)) return Result.NOT_ALLOWED
        val queue = queues.computeIfAbsent(display.id) { DisplayQueue() }
        if (synchronized(queue) { queue.setLoop(enabled) }) changed(display)
        return Result.OK
    }

    /** Jumps to the next entry now; whoever may change the video. */
    fun skip(display: DisplayData, ctx: PlaybackContext, actorId: UUID): Result {
        if (!settings().enabled) return Result.DISABLED
        if (display.virtual || !PlaybackPermissions.queueRuns(ctx.mode)) return Result.NOT_RUNNING
        if (!PlaybackPermissions.canSkipQueue(ctx)) return Result.NOT_ALLOWED
        val queue = queues[display.id] ?: return Result.EMPTY
        if (synchronized(queue) { queue.isEmpty() }) return Result.EMPTY
        if (!editThrottle.tryAcquire(actorId, EDIT_COOLDOWN_MS)) return Result.THROTTLED
        return if (advance(display) != null) Result.OK else Result.REFUSED
    }

    /** A client's preload progress for the head entry of [display]. */
    fun onPreload(display: DisplayData, playerId: UUID, itemId: String, state: FullscreenPrepareState) {
        if (!preloadThrottle.tryAcquire(playerId to display.id, PRELOAD_COOLDOWN_MS)) return
        val queue = queues[display.id] ?: return
        val head = synchronized(queue) { queue.head() } ?: return
        if (head.itemId != itemId) return
        val roster = rosters.compute(display.id) { _, existing ->
            if (existing?.prepareId == itemId) existing else FullscreenPrepareRoster(itemId, Long.MAX_VALUE)
        } ?: return
        roster.onDelivered(playerId, transport.nowMs())
        roster.onStatus(playerId, itemId, state)
    }

    /** The display's video was set by hand (menu, command, API): it no longer plays a queue entry. */
    fun onVideoSetByHand(display: DisplayData) {
        val queue = queues[display.id] ?: return
        val before = synchronized(queue) { queue.revision }
        val after = synchronized(queue) {
            queue.onVideoSetByHand(display.url)
            queue.revision
        }
        if (after != before) changed(display)
    }

    /** Duration (ms) of the queue entry [display] plays now, 0 when it plays none or that is unknown. */
    fun playingEntryDurationMs(display: DisplayData): Long {
        val queue = queues[display.id] ?: return 0L
        return synchronized(queue) { queue.current }?.takeIf { it.url == display.url }?.durationMs ?: 0L
    }

    /** Forgets a deleted display's queue. */
    fun onDisplayRemoved(displayId: UUID) {
        rosters.remove(displayId)
        armedAt.remove(displayId)
        if (queues.remove(displayId) != null) schedulePersist()
    }

    /** Sends [display]'s queue to [playerId] (RequestSync reply), if their client shows queues and there is one. */
    fun sendCurrent(display: DisplayData, playerId: UUID) {
        if (!settings().enabled || !isCapable(playerId)) return
        val queue = queues[display.id] ?: return
        val state = synchronized(queue) {
            if (queue.isEmpty() && !queue.loop && queue.current == null) null else stateOf(display.id, queue)
        } ?: return
        transport.sendTo(playerId, state)
    }

    /**
     * Called every couple of ticks: advances every display whose timeline reached (or ran past, however late this
     * tick comes) the end of its video while something is queued. Cheap when nothing is queued. With nothing to
     * advance to the display's video just loops.
     */
    fun tick() {
        if (queues.isEmpty() || !settings().enabled) return
        val now = transport.nowMs()
        for ((displayId, queue) in queues) {
            val (head, current) = synchronized(queue) { queue.head() to queue.current }
            if (head == null) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (display.virtual || !PlaybackPermissions.queueRuns(display.mode)) continue
            if (WatchPartyManager.hasSession(displayId) || TimelineManager.isHolding(displayId)) continue
            val timeline = TimelineManager.timelineOf(displayId) ?: continue
            val armed = armedAt[displayId] ?: Long.MIN_VALUE
            // No reported duration (e.g. the plugin reloaded and no client re-reported it): use the playing queue entry's
            val durationMs = timeline.durationMs.takeIf { it > 0L }
                ?: QueueTiming.scheduledDurationMs(current?.takeIf { it.url == display.url }?.durationMs ?: 0L)
            val earlyMs = if (readyFor(display, head).isEmpty()) UNPRELOADED_EARLY_MS else maxOf(
                ADVANCE_EARLY_MS, ReadinessTracker.leadMs(displayId, transport.nearbyPlayerIds(display)) + TICK_MARGIN_MS,
            )
            if (!QueueTiming.advanceDue(timeline, now, earlyMs, armed, durationMs)) continue
            advance(display, QueueTiming.switchAtMs(timeline, now, earlyMs, durationMs))
        }
    }

    /**
     * A client's stream reached its end on [display] (an older client looping its player): with something queued,
     * move on now, starting the next entry at the shared timeline's own end instant. Without, nothing: the timeline
     * wraps by itself.
     */
    fun onClientStreamEnded(display: DisplayData) {
        if (!settings().enabled || display.virtual || !PlaybackPermissions.queueRuns(display.mode)) return
        val queue = queues[display.id] ?: return
        val current = synchronized(queue) { if (queue.hasNext()) queue.current else return }
        if (WatchPartyManager.hasSession(display.id) || TimelineManager.isHolding(display.id)) return
        val timeline = TimelineManager.timelineOf(display.id) ?: return
        val durationMs = timeline.durationMs.takeIf { it > 0L }
            ?: QueueTiming.scheduledDurationMs(current?.takeIf { it.url == display.url }?.durationMs ?: 0L)
        val startAt = QueueTiming.switchAtMs(timeline, transport.nowMs(), QueueTiming.END_OF_STREAM_WINDOW_MS * 2, durationMs)
            ?: return
        advance(display, startAt)
    }

    /**
     * Moves [display] on to the next entry. An entry the platform refuses (addon veto) is dropped and the next one
     * tried, a few at most. Returns the entry now playing, or null.
     */
    private fun advance(display: DisplayData, startAtMs: Long? = null): QueueItem? {
        val queue = queues[display.id] ?: return null
        repeat(MAX_SKIPS_PER_ADVANCE) {
            val next = synchronized(queue) { queue.advance() } ?: run {
                changed(display)
                return null
            }
            val ready = readyFor(display, next)
            rosters.remove(display.id)
            val starts = runCatching { beforeStart(display, next) }
                .onFailure { logger.warn("Queue start hook failed for display {}.", display.id, it) }
                .getOrDefault(true)
            val applied = starts && runCatching { applyVideo(display, next, ready, startAtMs) }
                .onFailure { logger.warn("Could not switch display {} to its next queued video.", display.id, it) }
                .getOrDefault(false)
            if (applied) {
                logger.info(
                    "Display {} moved on to its next queued video ({} viewer(s) had it preloaded).", display.id, ready.size,
                )
                changed(display)
                return next
            }
        }
        changed(display)
        return null
    }

    /** The nearby queue clients that reported [next] READY: they hold it ready at 0 and need not be waited for. */
    private fun readyFor(display: DisplayData, next: QueueItem): List<UUID> {
        val roster = rosters[display.id]?.takeIf { it.prepareId == next.itemId } ?: return emptyList()
        return transport.nearbyPlayerIds(display).filter {
            isCapable(it) && roster.stateOf(it) == FullscreenPrepareState.READY
        }
    }

    private fun changed(display: DisplayData) {
        broadcastState(display)
        schedulePersist()
    }

    private fun broadcastState(display: DisplayData) {
        if (!settings().enabled) return
        val queue = queues[display.id] ?: return
        val state = synchronized(queue) { stateOf(display.id, queue) }
        transport.nearbyPlayerIds(display).filter(::isCapable).forEach { transport.sendTo(it, state) }
    }

    private fun stateOf(displayId: UUID, queue: DisplayQueue): QueueState {
        val s = settings()
        return QueueState(
            id = displayId,
            items = queue.items.map { it.toWire() },
            loop = queue.loop,
            maxItems = s.maxItems.coerceAtLeast(0),
            maxPerPlayer = s.maxPerPlayer.coerceAtLeast(0),
            revision = queue.revision,
            currentItemId = queue.current?.itemId ?: "",
        )
    }

    private fun newItemId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun snapshot(): Map<UUID, DisplayQueue> =
        queues.entries.associate { (id, queue) -> id to synchronized(queue) { queue.copy() } }

    private fun schedulePersist() {
        if (!persistScheduled.compareAndSet(false, true)) return
        ServerCoroutines.io.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistScheduled.set(false)
            synchronized(saveLock) { DisplayQueueStore.save(snapshot()) }
        }
    }
}
