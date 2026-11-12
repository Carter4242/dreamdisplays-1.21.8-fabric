package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.sync.DisplayFollowers
import com.dreamdisplays.shared.playback.PipPinSession
import com.dreamdisplays.shared.playback.ReadinessState
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.storage.PipPinStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks which displays each player has pinned to a `Picture-in-Picture` overlay, so a pinned display's updates keep
 * reaching them out of range. A pinner whose client shows the display ([viewersOf]) is one of its viewers at any
 * distance: it may act on it as from its range, and starts, the queue and auto-pause count it like a player there.
 * Pins are kept across quits and restarts (the client reopens its PiP on join); only online pinners count.
 */
object PipPinManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var transport: PlaybackTransport
    private val pins = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val throttle = ActionThrottle()
    private const val PIN_COOLDOWN_MS = 250L
    private const val MAX_PINS_PER_PLAYER = 16
    private const val PERSIST_DEBOUNCE_MS = 2_000L
    /** Every save holds it: the debounced one and [flush] write the same temporary file. */
    private val saveLock = Any()
    private val persistScheduled = AtomicBoolean(false)

    /** Wires the platform transport; pinners get a pinned display's timeline at any distance, PiP viewers count as its audience. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
        TimelineManager.pinReceivers = ::pinnersOf
        TimelineManager.pinViewers = ::viewersOf
    }

    /**
     * Records that [playerId] pinned [displayId] to PiP. Rate-limited per player, capped at
     * [MAX_PINS_PER_PLAYER] live pins, and a no-op when [displayId] doesn't reference a real display —
     * otherwise a hostile client could flood the store with garbage ids for free.
     */
    fun pin(playerId: UUID, displayId: UUID) {
        if (DisplayManager.getDisplayData(displayId) == null) return
        if (!throttle.tryAcquire(playerId, PIN_COOLDOWN_MS)) return
        val displayIds = pins.getOrPut(playerId) { ConcurrentHashMap.newKeySet() }
        if (displayId !in displayIds && displayIds.size >= MAX_PINS_PER_PLAYER) return
        displayIds.add(displayId)
        schedulePersist()
    }

    /**
     * Records that [playerId] unpinned [displayId] from PiP. Never throttled: a dropped unpin (one right after a pin)
     * would leave a pin that sends them that display's updates for good, and a removal cannot grow the store.
     */
    fun unpin(playerId: UUID, displayId: UUID) {
        if (pins[playerId]?.remove(displayId) == true) schedulePersist()
    }

    /** Online players who have [displayId] pinned: they get its timeline, description and queue at any distance. */
    fun pinnersOf(displayId: UUID): Collection<UUID> {
        if (pins.isEmpty()) return emptyList()
        val pinners = pins.entries.filter { displayId in it.value }.map { it.key }
        if (pinners.isEmpty()) return pinners
        val online = transport.onlinePlayerIds().toSet()
        return pinners.filter { it in online }
    }

    /**
     * Online players watching [displayId] in PiP: pinners whose client did not report it absent (a pin outliving the
     * client's PiP, which reports the display it no longer plays as absent, drops out here).
     */
    fun viewersOf(displayId: UUID): List<UUID> =
        DisplayFollowers.showing(pinnersOf(displayId)) { absent(displayId, it) }

    /** True when [playerId] (online) watches [displayId] in PiP: they may act on it as from its range. */
    fun isViewing(playerId: UUID, displayId: UUID): Boolean =
        pins[playerId]?.contains(displayId) == true && !absent(displayId, playerId)

    private fun absent(displayId: UUID, playerId: UUID): Boolean =
        ReadinessTracker.reportOf(displayId, playerId)?.state == ReadinessState.ABSENT

    /**
     * Re-sends every pinned display's info to [playerId] on join, bypassing render distance; also after a world change,
     * where the client drops a carried PiP that is not announced again.
     */
    fun onPlayerJoin(playerId: UUID) {
        val displayIds = pins[playerId] ?: return
        for (displayId in displayIds) {
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            transport.sendDisplayInfo(playerId, display, forced = true)
        }
    }

    /** Forgets every pin referencing a now-deleted display. */
    fun onDisplayRemoved(displayId: UUID) {
        var changed = false
        for (displayIds in pins.values) {
            if (displayIds.remove(displayId)) changed = true
        }
        if (changed) schedulePersist()
    }

    /** Restores persisted pins from disk; call once at startup. */
    /** Forgets every pin without touching the store: the server stopped; the next one restores its own. */
    fun forgetAll() {
        pins.clear()
        persistScheduled.set(false)
    }

    fun restore() {
        val records = PipPinStore.load()
        if (records.isEmpty()) return
        var restored = 0
        for (record in records) {
            val playerId = runCatching { UUID.fromString(record.playerId) }.getOrNull() ?: continue
            val displayId = runCatching { UUID.fromString(record.displayId) }.getOrNull() ?: continue
            pins.getOrPut(playerId) { ConcurrentHashMap.newKeySet() }.add(displayId)
            restored++
        }
        if (restored > 0) logger.info("Restored $restored persisted PiP pin(s).")
    }

    private fun schedulePersist() {
        if (!persistScheduled.compareAndSet(false, true)) return
        ServerCoroutines.io.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistScheduled.set(false)
            synchronized(saveLock) { persistNow() }
        }.invokeOnCompletion {
            // Cancelled while it waited (the server stopped): the next server in this process must still save
            persistScheduled.set(false)
        }
    }

    /** Writes the pins now (plugin disable): a debounced save still waiting would be cancelled with the plugin. */
    fun flush() {
        runCatching { synchronized(saveLock) { persistNow() } }
            .onFailure { logger.warn("Could not save PiP pins.", it) }
    }

    private fun persistNow() {
        val records = pins.flatMap { (playerId, displayIds) ->
            displayIds.map { displayId -> PipPinSession(playerId.toString(), displayId.toString()) }
        }
        PipPinStore.save(records)
    }
}
