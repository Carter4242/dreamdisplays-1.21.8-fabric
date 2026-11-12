package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.display.region.RegionShape
import com.dreamdisplays.core.display.region.RegionVisibility
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The `WorldGuard` region shape the server last sent per display (`DisplayInfo.region`), kept like [DisplayPolicyCache]
 * also for displays that are not loaded, so the load range and the sound fade of a region display
 * (`visibility.region`, see [RegionVisibility]) are computed here, every tick, with no traffic.
 */
object DisplayRegions {
    private val shapes = ConcurrentHashMap<UUID, RegionShape>()

    /** Records the region [packet] carries (or forgets it when there is none). */
    fun update(packet: DisplayInfo) {
        val shape = packet.region?.toShape()
        if (shape == null) shapes.remove(packet.id) else shapes[packet.id] = shape
    }

    fun get(displayId: UUID): RegionShape? = shapes[displayId]

    fun forget(displayId: UUID) {
        shapes.remove(displayId)
    }

    fun clear() = shapes.clear()

    /**
     * Whether the display may be loaded by a player at [x] [y] [z]: always without a region; within its load distance
     * (plus [slackBlocks]) with one; never when its policy names a region the server sent no shape for.
     */
    fun mayLoad(displayId: UUID, policy: DisplayPolicy?, x: Double, y: Double, z: Double, slackBlocks: Double = 0.0): Boolean =
        RegionVisibility.clientMayLoad(policy, shapes[displayId], x, y, z, slackBlocks)

    /** The sound multiplier for a player at [x] [y] [z]: 1 without a region, else the region's fade. */
    fun fade(displayId: UUID, policy: DisplayPolicy?, x: Double, y: Double, z: Double): Double =
        RegionVisibility.clientFade(policy, shapes[displayId], x, y, z)
}
