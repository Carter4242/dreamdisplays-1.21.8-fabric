package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.display.policy.DisplayPolicy
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Last [DisplayPolicy] the server sent per display, kept for displays that have no [DisplayScreen] yet
 * (out of range, cached as unloaded) so their visibility distance and starting volume still apply.
 * Loaded screens read their own [DisplayScreen.policy].
 */
object DisplayPolicyCache {
    private val policies = ConcurrentHashMap<UUID, DisplayPolicy>()

    /** Records the policy carried in [customData] (or forgets it when there is none) and returns it. */
    fun update(displayId: UUID, customData: Map<String, ByteArray>): DisplayPolicy? {
        val policy = DisplayPolicy.decode(customData[DisplayPolicy.KEY])
        if (policy == null) policies.remove(displayId) else policies[displayId] = policy
        return policy
    }

    fun get(displayId: UUID): DisplayPolicy? = policies[displayId]

    fun forget(displayId: UUID) {
        policies.remove(displayId)
    }

    fun clear() = policies.clear()

    /** The player's render distance, further limited by [policy]'s `visibility.maxDistanceBlocks` when set. */
    fun renderDistanceFor(policy: DisplayPolicy?): Int {
        val own = DisplayScreen.clientRenderDistanceBlocks(policy)
        val max = policy?.visibility?.maxDistanceBlocks?.takeIf { it > 0 } ?: return own
        return minOf(own, max.toInt().coerceAtLeast(1))
    }
}
