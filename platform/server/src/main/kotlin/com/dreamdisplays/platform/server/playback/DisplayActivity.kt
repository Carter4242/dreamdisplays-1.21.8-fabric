package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.core.activity.ActivityLog
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import com.dreamdisplays.core.protocol.common.packets.ActivityState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.storage.ActivityStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Each display's Activity list: the last `[activity] max_entries` things anyone could notice happening on it (who
 * played, seeked, set the video, queued, ...), and what happened by itself (the queue moved on, the video looped).
 * Rows are recorded where an action is actually applied, never for refused attempts ([record]). Players with a
 * display's Activity tab open get the whole list once ([subscribe]) and then each new row; nobody else gets anything.
 * Persisted next to the queues ([ActivityStore]) and dropped with the display ([forget]).
 */
object DisplayActivity {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val PERSIST_DEBOUNCE_MS = 5_000L

    /** `[activity] max_entries` (0 = recording off). */
    var maxEntries: () -> Int = { 100 }

    /** True when [playerId] may get [displayId]'s Activity right now (allowed to see it, and at the display). */
    var mayView: (playerId: UUID, displayId: UUID) -> Boolean = { _, _ -> false }

    private val log = ActivityLog { maxEntries() }
    private var transport: PlaybackTransport? = null

    /** Player -> the display whose Activity tab they have open. */
    private val watchers = ConcurrentHashMap<UUID, UUID>()
    private val saveLock = Any()
    private val persistScheduled = AtomicBoolean(false)

    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /** Loads the stored rows of displays that still exist; call at startup, after the displays are registered. */
    fun restore() {
        watchers.clear()
        runCatching { log.load(ActivityStore.load()) }
            .onFailure { logger.warn("Could not read the display activity.", it) }
        log.retainOnly(DisplayManager.getDisplays().map { it.id }.toSet())
    }

    /** Writes every display's rows now (plugin disable). */
    fun flush() {
        runCatching { synchronized(saveLock) { ActivityStore.save(log.toRecords()) } }
            .onFailure { logger.warn("Could not save display activity.", it) }
    }

    /** Who runs the `/display` command being handled on this thread: a player's id, or [CONSOLE]; null outside one. */
    private val commandSender = ThreadLocal<UUID?>()
    private val CONSOLE = UUID(0L, 1L)

    /**
     * Runs [block] (a `/display` command) with its sender as the actor of every row it records: [playerId], or the
     * console when null. Commands pass ids around for their own checks (the display's owner for the console), so the
     * sender is taken from here instead.
     */
    fun <T> asCommandSender(playerId: UUID?, block: () -> T): T {
        val previous = commandSender.get()
        commandSender.set(playerId ?: CONSOLE)
        try {
            return block()
        } finally {
            if (previous == null) commandSender.remove() else commandSender.set(previous)
        }
    }

    /**
     * Records that [kind] happened on [display] (by [actor], or by itself when null; [console]: by the server console).
     * Inside a command ([asCommandSender]) its sender is the actor instead, except for [ActivityKind.automatic] rows
     * and rows [about][ActivityKind.aboutActor] a given player. [actorName] defaults to the actor's current name.
     * Never throws: a broken row must not break the action itself.
     */
    fun record(
        display: DisplayData?, kind: ActivityKind, actor: UUID? = null, actorName: String? = null,
        title: String = "", url: String = "", fromMs: Long = 0, toMs: Long = 0, rate: Double = 0.0, count: Int = 0,
        flag: Boolean = false, text: String = "", text2: String = "", console: Boolean = false,
    ) {
        display ?: return
        runCatching {
            // Only a platform that serves the tab (Paper) records: the others never call init
            if (transport == null || maxEntries() <= 0) return
            val sender = commandSender.get()?.takeIf { !kind.automatic && !(kind.aboutActor && actor != null) }
            val byConsole = console || sender == CONSOLE
            val who = if (kind.automatic || byConsole) null else sender ?: actor
            val name = if (who == actor && actorName != null) actorName else who?.let { transport?.playerName(it) }.orEmpty()
            val entry = ActivityEntryWire(
                kind = kind.wire, actorId = who ?: ZERO_UUID, actorName = name, title = title, url = url,
                fromMs = fromMs, toMs = toMs, rate = rate, count = count, flag = flag, text = text, text2 = text2,
                console = byConsole,
            )
            val row = log.record(display.id, entry, System.currentTimeMillis()) ?: return
            schedulePersist()
            push(display.id, row)
        }.onFailure { logger.warn("Could not record {} on display {}.", kind, display.id, it) }
    }

    /** Records [kind] on the display with id [displayId], if it exists. */
    fun record(displayId: UUID, kind: ActivityKind, actor: UUID? = null, text: String = "", count: Int = 0, flag: Boolean = false) =
        record(DisplayManager.getDisplayData(displayId), kind, actor, text = text, count = count, flag = flag)

    /** [added] of [entries] went into [display]'s queue for [actorId]: one video, or a playlist. */
    fun recordAdd(display: DisplayData, actorId: UUID, actorName: String, entries: List<QueueManager.NewEntry>, added: Int) {
        val first = entries.firstOrNull() ?: return
        if (entries.size == 1) record(display, ActivityKind.QUEUE_ADD, actorId, actorName, title = first.title, url = first.url)
        else record(display, ActivityKind.QUEUE_ADD_PLAYLIST, actorId, actorName, count = added)
    }

    /** [actorId] moved queue entry [itemId] of [display] from index [from]; [items] is the queue after the move. */
    fun recordMove(display: DisplayData, actorId: UUID, items: List<com.dreamdisplays.core.queue.QueueItem>, itemId: String, from: Int) {
        val to = items.indexOfFirst { it.itemId == itemId }
        val item = items.getOrNull(to) ?: return
        record(display, ActivityKind.QUEUE_MOVE, actorId, title = item.title, url = item.url, fromMs = from + 1L, toMs = to + 1L)
    }

    /** [display] was moved (and maybe resized: it was [sizeBefore], width to height, before). */
    fun recordMoved(display: DisplayData, sizeBefore: Pair<Int, Int>) {
        if (sizeBefore == display.width to display.height) record(display, ActivityKind.MOVED)
        else record(display, ActivityKind.RESIZED, count = display.width, toMs = display.height.toLong())
    }

    /** Records that [display]'s queue moved on by itself to the entry it is given. */
    fun movedOn(display: DisplayData): (com.dreamdisplays.core.queue.QueueItem) -> Unit = { item ->
        record(display, ActivityKind.QUEUE_NEXT, title = item.title, url = item.url)
    }

    /** The title [display] knows for [url] (a queue entry's), or empty. */
    fun titleFor(display: DisplayData, url: String): String {
        if (url.isBlank()) return ""
        val queue = QueueManager.snapshotOf(display.id) ?: return ""
        return (listOfNotNull(queue.current) + queue.items).firstOrNull { it.url == url }?.title.orEmpty()
    }

    /**
     * [playerId] opened ([open]) or closed [displayId]'s Activity tab. Opening sends the whole list (when they may see
     * it) and follows the display from then on; a player follows one display at a time.
     */
    fun subscribe(playerId: UUID, displayId: UUID, open: Boolean) {
        if (!open || maxEntries() <= 0 || !mayView(playerId, displayId)) {
            watchers.remove(playerId)
            return
        }
        watchers[playerId] = displayId
        transport?.sendTo(playerId, ActivityState(displayId, log.entries(displayId), replace = true, System.currentTimeMillis()))
    }

    /** [displayId]'s rows, newest first. */
    fun entries(displayId: UUID): List<ActivityEntryWire> = log.entries(displayId)

    /** Forgets a deleted display's rows. */
    fun forget(displayId: UUID) {
        watchers.values.removeIf { it == displayId }
        if (log.forget(displayId)) schedulePersist()
    }

    fun onQuit(playerId: UUID) {
        watchers.remove(playerId)
    }

    /** Forgets everything in memory (an integrated server stopped). */
    fun reset() {
        watchers.clear()
        log.clear()
    }

    private fun push(displayId: UUID, row: ActivityEntryWire) {
        if (watchers.isEmpty()) return
        val transport = transport ?: return
        val packet = ActivityState(displayId, [row], replace = false, serverTimeMs = System.currentTimeMillis())
        for ((playerId, watched) in watchers) {
            if (watched != displayId) continue
            // Walked away or lost the right to see it since opening the tab: no more rows
            if (!mayView(playerId, displayId)) {
                watchers.remove(playerId, displayId)
                continue
            }
            transport.sendTo(playerId, packet)
        }
    }

    private fun schedulePersist() {
        if (!persistScheduled.compareAndSet(false, true)) return
        ServerCoroutines.io.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistScheduled.set(false)
            synchronized(saveLock) { ActivityStore.save(log.toRecords()) }
        }.invokeOnCompletion {
            persistScheduled.set(false)
        }
    }
}
