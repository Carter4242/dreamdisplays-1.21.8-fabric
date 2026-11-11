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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * nearby client is READY the next video starts after [Settings.readyHoldMs] instead of the normal start hold. A
 * client without the queue (`ClientHello.supportsQueue` unset) gets no queue packets and just sees the video change.
 * All queue edits run under the queue's own lock.
 */
object QueueManager {
    /** Server-wide knobs, `[queue]` in the config. */
    data class Settings(
        val enabled: Boolean = true,
        val maxItems: Int = 50,
        val maxPerPlayer: Int = 10,
        val readyHoldMs: Long = 300L,
    )

    /** Outcome of a queue edit, for command / chat feedback. */
    enum class Result { OK, DISABLED, NOT_ALLOWED, NOT_RUNNING, FULL, PLAYER_LIMIT, NOT_FOUND, EMPTY, THROTTLED, REFUSED }

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Advance this long before the end, so no client wraps its looping player back to frame 0 first. */
    private const val ADVANCE_EARLY_MS = 250L

    /** A looping timeline the server ticked past the end of is still advanced within this long of the wrap. */
    private const val LATE_GRACE_MS = 2_000L
    private const val ADD_COOLDOWN_MS = 750L
    private const val EDIT_COOLDOWN_MS = 150L
    private const val PRELOAD_COOLDOWN_MS = 100L
    private const val MAX_SKIPS_PER_ADVANCE = 5
    private const val PERSIST_DEBOUNCE_MS = 2_000L

    private lateinit var transport: PlaybackTransport

    /** Where the platform's `[queue]` settings come from. */
    var settings: () -> Settings = { Settings() }

    /**
     * Switches [display] to [item] the way a player-set video would (addon veto, save, broadcast, timeline reset with
     * a start hold of [holdMs], or the default hold when null). Returns false when the change was refused.
     */
    var applyVideo: (display: DisplayData, item: QueueItem, holdMs: Long?) -> Boolean = { _, _, _ -> false }

    private val queues = ConcurrentHashMap<UUID, DisplayQueue>()
    private val rosters = ConcurrentHashMap<UUID, FullscreenPrepareRoster>()
    private val addThrottle = ActionThrottle()
    private val editThrottle = ActionThrottle()
    private val preloadThrottle = ActionThrottle()
    private val persistMutex = Mutex()
    private val persistScheduled = AtomicBoolean(false)

    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /** Loads the persisted queues; call once at startup, after the displays are registered. */
    fun restore() {
        queues.clear()
        rosters.clear()
        val loaded = DisplayQueueStore.load()
        var entries = 0
        for ((displayId, queue) in loaded) {
            if (DisplayManager.getDisplayData(displayId) == null) continue
            queues[displayId] = queue
            entries += queue.size
        }
        if (entries > 0) logger.info("Restored {} queued video(s) on {} display(s).", entries, queues.size)
    }

    /** Writes every queue now (plugin disable). */
    fun flush() {
        runCatching { DisplayQueueStore.save(snapshot()) }
            .onFailure { logger.warn("Could not save display queues.", it) }
    }

    /** True when [playerId]'s client shows the queue and preloads. */
    fun isCapable(playerId: UUID): Boolean = V2PlayerTracker.helloOf(playerId)?.supportsQueue == true

    private fun limits(): QueueLimits = settings().let { QueueLimits(it.maxItems, it.maxPerPlayer) }

    /** A copy of [displayId]'s queue, or null when it has none. */
    fun snapshotOf(displayId: UUID): DisplayQueue? = queues[displayId]?.let { synchronized(it) { it.copy() } }

    /** Adds a video to [display]'s queue for [actorId]; [url] and [lang] must already be validated by the caller. */
    fun add(
        display: DisplayData, ctx: PlaybackContext, actorId: UUID, actorName: String,
        url: String, lang: String, title: String, durationMs: Long, thumbnailUrl: String,
        throttled: Boolean = true,
    ): Result {
        if (!settings().enabled) return Result.DISABLED
        if (display.virtual || !PlaybackPermissions.queueRuns(ctx.mode)) return Result.NOT_RUNNING
        if (!PlaybackPermissions.canAddToQueue(ctx)) return Result.NOT_ALLOWED
        if (url.isBlank() || url.length > QueueText.MAX_URL) return Result.REFUSED
        if (throttled && !addThrottle.tryAcquire(actorId, ADD_COOLDOWN_MS)) return Result.THROTTLED

        val item = QueueItem(
            itemId = newItemId(),
            url = url,
            lang = lang,
            title = QueueText.cleanTitle(title),
            durationMs = QueueText.cleanDuration(durationMs),
            thumbnailUrl = QueueText.cleanThumbnail(thumbnailUrl),
            addedBy = actorId,
            addedByName = QueueText.cleanName(actorName),
            addedAtMs = transport.nowMs(),
        )
        val queue = queues.computeIfAbsent(display.id) { DisplayQueue() }
        val result = synchronized(queue) { queue.add(item, limits(), exemptFromPlayerLimit = ctx.isOwner || ctx.isAdmin) }
        when (result) {
            QueueAddResult.FULL -> return Result.FULL
            QueueAddResult.PLAYER_LIMIT -> return Result.PLAYER_LIMIT
            QueueAddResult.DUPLICATE -> return Result.REFUSED
            QueueAddResult.ADDED -> {}
        }
        changed(display)
        // Nothing on the display yet: the first queued video starts right away
        if (display.url.isBlank()) advance(display)
        return Result.OK
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

    /** Forgets a deleted display's queue. */
    fun onDisplayRemoved(displayId: UUID) {
        rosters.remove(displayId)
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
     * Called every couple of ticks: advances every display whose timeline reached the end of its video while
     * something is queued. Cheap when nothing is queued.
     */
    fun tick() {
        if (queues.isEmpty() || !settings().enabled) return
        val now = transport.nowMs()
        for ((displayId, queue) in queues) {
            if (synchronized(queue) { queue.isEmpty() }) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (display.virtual || !PlaybackPermissions.queueRuns(display.mode)) continue
            if (WatchPartyManager.hasSession(displayId) || TimelineManager.isHolding(displayId)) continue
            val timeline = TimelineManager.timelineOf(displayId) ?: continue
            if (!QueueTiming.advanceDue(timeline, now, ADVANCE_EARLY_MS, LATE_GRACE_MS)) continue
            advance(display)
        }
    }

    /**
     * Moves [display] on to the next entry. An entry the platform refuses (addon veto) is dropped and the next one
     * tried, a few at most. Returns the entry now playing, or null.
     */
    private fun advance(display: DisplayData): QueueItem? {
        val queue = queues[display.id] ?: return null
        repeat(MAX_SKIPS_PER_ADVANCE) {
            val next = synchronized(queue) { queue.advance() } ?: run {
                changed(display)
                return null
            }
            val hold = holdFor(display, next)
            rosters.remove(display.id)
            val applied = runCatching { applyVideo(display, next, hold) }
                .onFailure { logger.warn("Could not switch display {} to its next queued video.", display.id, it) }
                .getOrDefault(false)
            if (applied) {
                logger.info(
                    "Display {} moved on to its next queued video{}.", display.id,
                    if (hold != null) " (every viewer had it ready, ${hold} ms hold)" else "",
                )
                changed(display)
                return next
            }
        }
        changed(display)
        return null
    }

    /**
     * The short hold when every nearby queue client reported [next] READY, else null (the normal start hold). A nearby
     * client that never reported counts as still loading; one that walked away no longer counts.
     */
    private fun holdFor(display: DisplayData, next: QueueItem): Long? {
        val roster = rosters[display.id]?.takeIf { it.prepareId == next.itemId } ?: return null
        val nearby = transport.nearbyPlayerIds(display).filter(::isCapable).toSet()
        roster.delivered.filter { it !in nearby }.forEach(roster::forget)
        val now = transport.nowMs()
        nearby.forEach { roster.onDelivered(it, now) }
        return if (roster.tally().allReady) settings().readyHoldMs else null
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
            persistMutex.withLock { DisplayQueueStore.save(snapshot()) }
        }
    }
}
