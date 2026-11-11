package com.dreamdisplays.shared.playback

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Bookkeeping for one fullscreen preparation: which players it was delivered to, how far each of their clients got,
 * and which targeted players run a client too old to be prepared. Platform-neutral so it can be tested on its own.
 */
class FullscreenPrepareRoster(val prepareId: String, val expiresAtMs: Long) {
    /** Counts for `/display fullscreen list` and the prepare reply. */
    data class Tally(
        val delivered: Int,
        val ready: Int,
        val loading: Int,
        val failed: Int,
        val disabled: Int,
        val unsupported: Int,
    ) {
        /** True once every client that can be prepared is ready (players with displays off do not hold it up). */
        val settled: Boolean get() = loading == 0

        /** True when at least one client was prepared and every client that can be is ready. */
        val allReady: Boolean get() = ready > 0 && loading == 0 && failed == 0
    }

    private val states = ConcurrentHashMap<UUID, FullscreenPrepareState>()
    private val lastDeliveryMs = ConcurrentHashMap<UUID, Long>()
    private val unsupported: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Players the preparation was delivered to and not withdrawn from. */
    val delivered: Set<UUID> get() = states.keys.toSet()

    /** True if [playerId] currently holds this preparation. */
    fun isDelivered(playerId: UUID): Boolean = states.containsKey(playerId)

    /** Records a delivery (or re-delivery) to [playerId] at [nowMs]; true the first time. */
    fun onDelivered(playerId: UUID, nowMs: Long): Boolean {
        lastDeliveryMs[playerId] = nowMs
        unsupported.remove(playerId)
        return states.putIfAbsent(playerId, FullscreenPrepareState.LOADING) == null
    }

    /** Notes a targeted player whose client cannot be prepared; it plays the broadcast the old way. */
    fun onUnsupported(playerId: UUID) {
        if (!states.containsKey(playerId)) unsupported.add(playerId)
    }

    /**
     * Applies a client's report. Returns false (and changes nothing) when it is for another preparation or from a
     * player this one was never delivered to.
     */
    fun onStatus(playerId: UUID, prepareId: String, state: FullscreenPrepareState): Boolean {
        if (prepareId != this.prepareId) return false
        if (states.computeIfPresent(playerId) { _, _ -> state } == null) return false
        return true
    }

    /** The last reported state of [playerId], or null if it does not hold this preparation. */
    fun stateOf(playerId: UUID): FullscreenPrepareState? = states[playerId]

    /**
     * Players still loading whose last delivery is at least [retryMs] old: the display may never have arrived (it was
     * turned off, or the player changed worlds and dropped it), so a fresh delivery can only help.
     */
    fun dueForRedelivery(nowMs: Long, retryMs: Long): List<UUID> =
        states.entries
            .filter { it.value == FullscreenPrepareState.LOADING && nowMs - (lastDeliveryMs[it.key] ?: 0L) >= retryMs }
            .map { it.key }

    /** Drops every trace of [playerId] (left, no longer targeted); true if it held the preparation. */
    fun forget(playerId: UUID): Boolean {
        unsupported.remove(playerId)
        lastDeliveryMs.remove(playerId)
        return states.remove(playerId) != null
    }

    /** True once [nowMs] reached the expiry. */
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** Current counts. */
    fun tally(): Tally {
        val snapshot = states.values.toList()
        return Tally(
            delivered = snapshot.size,
            ready = snapshot.count { it == FullscreenPrepareState.READY },
            loading = snapshot.count { it == FullscreenPrepareState.LOADING },
            failed = snapshot.count { it == FullscreenPrepareState.FAILED },
            disabled = snapshot.count { it == FullscreenPrepareState.DISABLED },
            unsupported = unsupported.size,
        )
    }
}
