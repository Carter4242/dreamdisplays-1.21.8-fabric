package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.core.activity.ActivityJournalFormat
import com.dreamdisplays.core.activity.ActivityLimits
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

    /** Rows recorded on each thread so far (a packet that recorded none was refused: see `ActivityAttempts`). */
    private val recordedHere = ThreadLocal.withInitial { 0L }

    fun recordedOnThisThread(): Long = recordedHere.get()

    /** The player whose packet this thread handles (null: none); the actor of a row recorded without one. */
    private val packetSender = ThreadLocal<UUID?>()

    fun handlingPacketFrom(playerId: UUID?) {
        if (playerId == null) packetSender.remove() else packetSender.set(playerId)
    }
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
     * and rows [about][ActivityKind.aboutActor] a given player. [actorName] defaults to the actor's current name; [old]
     * is the value the action replaced, for the permanent journal ([ActivityJournal]), which gets every row.
     * Never throws: a broken row must not break the action itself.
     */
    fun record(
        display: DisplayData?, kind: ActivityKind, actor: UUID? = null, actorName: String? = null,
        title: String = "", url: String = "", fromMs: Long = 0, toMs: Long = 0, rate: Double = 0.0, count: Int = 0,
        flag: Boolean = false, text: String = "", text2: String = "", console: Boolean = false, old: String = "",
    ) {
        display ?: return
        runCatching {
            // Only a platform that serves the tab (Paper) records: the others never call init
            if (transport == null) return
            recordedHere.set(recordedHere.get() + 1)
            val entry = entryOf(kind, actor, actorName, console).copy(
                title = title, url = url, fromMs = fromMs, toMs = toMs, rate = rate, count = count, flag = flag,
                text = text, text2 = text2,
            )
            val now = System.currentTimeMillis()
            journal(display, ActivityLimits.clean(entry).copy(atMs = now), old)
            if (maxEntries() <= 0) return
            val row = log.record(display.id, entry, now) ?: return
            schedulePersist()
            push(display.id, row)
        }.onFailure { logger.warn("Could not record {} on display {}.", kind, display.id, it) }
    }

    /**
     * [actor] tried [kind] on [displayId] and was turned down ([reason]): journal only, never in the list. The same
     * refusal from the same player on the same display is written at most once a second, with how many it stands for.
     */
    fun refused(displayId: UUID, kind: ActivityKind, actor: UUID, actorName: String, reason: String, url: String = "", toMs: Long = 0) {
        runCatching {
            if (transport == null) return
            val now = System.currentTimeMillis()
            val key = Triple(actor, displayId, kind)
            var repeats = 0
            val write = synchronized(refusals) {
                val last = refusals[key]
                if (last != null && now - last.first < REFUSAL_EVERY_MS) {
                    refusals[key] = last.first to last.second + 1
                    false
                } else {
                    repeats = last?.second ?: 0
                    refusals[key] = now to 0
                    if (refusals.size > MAX_REFUSAL_KEYS) refusals.clear()
                    true
                }
            }
            if (!write) return
            val entry = ActivityLimits.clean(
                ActivityEntryWire(atMs = now, kind = kind.wire, actorId = actor, actorName = actorName, url = url, toMs = toMs),
            )
            val display = DisplayManager.getDisplayData(displayId)
            ActivityJournal.write(
                ActivityJournalFormat.line(entry, displayId, display?.name, display?.let(placeOf), refused = reason, repeats = repeats),
            )
        }.onFailure { logger.warn("Could not journal a refused {} on display {}.", kind, displayId, it) }
    }

    private const val REFUSAL_EVERY_MS = 1_000L
    private const val MAX_REFUSAL_KEYS = 10_000
    private val refusals = HashMap<Triple<UUID, UUID, ActivityKind>, Pair<Long, Int>>()

    /** Where a display stands, for the journal (the platform sets it). */
    var placeOf: (DisplayData) -> ActivityJournalFormat.Place? = { null }

    /** The row skeleton for [kind] by [actor]: inside a command its sender acts instead (see [record]). */
    private fun entryOf(kind: ActivityKind, actor: UUID?, actorName: String?, console: Boolean): ActivityEntryWire {
        val sender = commandSender.get()?.takeIf { !kind.automatic && !(kind.aboutActor && actor != null) }
        val byConsole = console || sender == CONSOLE
        val who = if (kind.automatic || byConsole) null else sender ?: actor ?: packetSender.get()
        val name = if (who == actor && actorName != null) actorName else who?.let { transport?.playerName(it) }.orEmpty()
        return ActivityEntryWire(kind = kind.wire, actorId = who ?: ZERO_UUID, actorName = name, console = byConsole)
    }

    private fun journal(display: DisplayData, row: ActivityEntryWire, old: String) {
        val cleanOld = old.filter { !it.isISOControl() }.take(ActivityLimits.MAX_URL)
        ActivityJournal.write(ActivityJournalFormat.line(row, display.id, display.name, placeOf(display), cleanOld))
    }

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
