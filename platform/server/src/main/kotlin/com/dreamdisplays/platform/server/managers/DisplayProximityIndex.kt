package com.dreamdisplays.platform.server.managers

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches which players are near which displays for `Folia`, whose global region coordinators
 * cannot read entity locations directly. Populated per-player by each player's own entity
 * scheduler task, then read back by the global coordinator in [DisplayManager].
 */
internal class DisplayProximityIndex {
    private val nearbyPlayersByDisplay: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()
    private val nearbyDisplaysByPlayer: MutableMap<UUID, Set<UUID>> = ConcurrentHashMap()

    /** Removes a display from the cached proximity index. */
    fun forgetDisplay(displayId: UUID) {
        nearbyPlayersByDisplay.remove(displayId)
        nearbyDisplaysByPlayer.replaceAll { _, ids -> ids - displayId }
    }

    /** Forgets everything (the server this index belonged to stopped). */
    fun clear() {
        nearbyPlayersByDisplay.clear()
        nearbyDisplaysByPlayer.clear()
    }

    /** Removes [playerId] from the cached proximity index. */
    fun forgetPlayer(playerId: UUID) {
        nearbyDisplaysByPlayer.remove(playerId)?.forEach { displayId ->
            nearbyPlayersByDisplay[displayId]?.remove(playerId)
        }
    }

    /** Cached nearby player ids for [displayId], safe to read from the global coordinator. */
    fun trackedNearbyPlayerIds(displayId: UUID): List<UUID> =
        nearbyPlayersByDisplay[displayId]?.toList() ?: []

    /** Whether [playerId] was near [displayId] at their last update. */
    fun isTracked(displayId: UUID, playerId: UUID): Boolean = nearbyDisplaysByPlayer[playerId]?.contains(displayId) == true

    /**
     * Updates the cached index after [playerId]'s entity task computed their nearby displays. Returns the displays they
     * were near before, or null when the index knew nothing of them yet.
     */
    fun update(playerId: UUID, nearbyDisplayIds: Set<UUID>): Set<UUID>? {
        val known = nearbyDisplaysByPlayer.put(playerId, nearbyDisplayIds)
        val previous = known ?: emptySet()

        (previous - nearbyDisplayIds).forEach { displayId ->
            nearbyPlayersByDisplay[displayId]?.remove(playerId)
        }
        (nearbyDisplayIds - previous).forEach { displayId ->
            nearbyPlayersByDisplay.computeIfAbsent(displayId) { ConcurrentHashMap.newKeySet() }.add(playerId)
        }
        return known
    }
}
