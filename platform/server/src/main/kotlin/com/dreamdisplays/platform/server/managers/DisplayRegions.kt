package com.dreamdisplays.platform.server.managers

import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.display.region.RegionShape
import com.dreamdisplays.core.display.region.RegionVisibility
import com.dreamdisplays.core.display.region.RegionVisibility.Reason
import com.dreamdisplays.core.display.region.RegionVisibility.State
import com.dreamdisplays.core.protocol.common.packets.DisplayRegionShape
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.World
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The `WorldGuard` region each display is tied to (`visibility.region`), resolved to a [State] and cached, so deciding who
 * gets a display costs a map read and a little arithmetic per player: no `WorldGuard` call, no policy decoding.
 *
 * An entry is rebuilt when the display's policy override or the server defaults change (compared by identity), or its
 * world does; the region itself is read again every [REFRESH_MS] by [refreshStale] (the once-a-second display update), so
 * a region that is redefined, deleted or created shows within seconds. Region reads only touch `WorldGuard`'s region
 * manager (thread-safe), never world or entity state, so any thread may resolve one (`Folia` included).
 */
@PaperOnly
object DisplayRegions {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val REFRESH_MS = 5_000L

    private class Entry(
        val policyBytes: ByteArray?,
        val defaults: DisplayPolicy,
        val worldName: String?,
        val state: State,
        val readAtMs: Long,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** [display]'s region state; [State.None] when it is tied to no region. */
    fun stateOf(display: PaperDisplayData): State {
        val bytes = display.customData[DisplayPolicy.KEY]
        val defaults = DisplayPolicies.defaults()
        val worldName = display.pos1.world?.name
        val entry = entries[display.id]
        if (entry != null && entry.policyBytes === bytes && entry.defaults === defaults && entry.worldName == worldName) {
            return entry.state
        }
        return build(display, bytes, defaults, worldName, entry, force = false).state
    }

    /** Reads the regions of [displays] again where the last read is older than [REFRESH_MS]. */
    fun refreshStale(displays: Collection<PaperDisplayData>) {
        val now = System.currentTimeMillis()
        if (entries.size > displays.size) entries.keys.retainAll(displays.mapTo(HashSet()) { it.id })
        for (display in displays) {
            val entry = entries[display.id] ?: continue
            if (entry.state === State.None || now - entry.readAtMs < REFRESH_MS) continue
            val rebuilt = build(display, display.customData[DisplayPolicy.KEY], DisplayPolicies.defaults(), display.pos1.world?.name, entry, force = true)
            if (rebuilt.state != entry.state) {
                logger.info("Display {} region: {} -> {}.", display.id, summary(entry.state), summary(rebuilt.state))
            }
        }
    }

    /** Forgets [displayId] (deleted). */
    fun forget(displayId: UUID) {
        entries.remove(displayId)
    }

    fun clear() = entries.clear()

    /**
     * What [display] would be tied to with `visibility.region` = [regionId] (its other settings as they are), read now:
     * for the policy command to refuse a region it cannot use before storing it.
     */
    fun preview(display: PaperDisplayData, regionId: String): State {
        val policy = DisplayPolicies.effective(display)
        return resolve(display, regionId, RegionVisibility.fadeBlocks(policy), RegionVisibility.loadBlocks(policy))
    }

    /** The shape to put in a `DisplayInfo` for [display], or null when it has no usable region. */
    fun wireShape(display: PaperDisplayData): DisplayRegionShape? =
        (stateOf(display) as? State.Active)?.let { DisplayRegionShape.of(it.shape) }

    private fun build(
        display: PaperDisplayData, bytes: ByteArray?, defaults: DisplayPolicy, worldName: String?, previous: Entry?, force: Boolean,
    ): Entry {
        val policy = (DisplayPolicy.decode(bytes) ?: DisplayPolicy.EMPTY).mergedOver(defaults)
        val regionId = RegionVisibility.regionId(policy)
        val fade = RegionVisibility.fadeBlocks(policy)
        val load = RegionVisibility.loadBlocks(policy)
        val now = System.currentTimeMillis()
        // Only the fade or load distance changed (or nothing did): the region read stays good until it is due again
        val reuse = !force && regionId != null && previous != null && previous.worldName == worldName &&
                now - previous.readAtMs < REFRESH_MS && sameRegion(previous.state, regionId)
        val state = when {
            regionId == null -> State.None
            reuse -> withDistances(previous!!.state, fade, load)
            else -> resolve(display, regionId, fade, load)
        }
        val readAt = if (reuse) previous!!.readAtMs else now
        return Entry(bytes, defaults, worldName, state, readAt).also { entries[display.id] = it }
    }

    private fun sameRegion(state: State, regionId: String): Boolean = when (state) {
        State.None -> false
        is State.Active -> state.regionId.equals(regionId, ignoreCase = true)
        is State.Unavailable -> state.regionId.equals(regionId, ignoreCase = true)
    }

    private fun withDistances(state: State, fade: Double, load: Double): State =
        if (state is State.Active) state.copy(fadeBlocks = fade, loadBlocks = load) else state

    private fun resolve(display: PaperDisplayData, regionId: String, fade: Double, load: Double): State {
        if (display.virtual) return State.Unavailable(regionId, Reason.VIRTUAL)
        if (!WorldGuardRegions.isAvailable()) return State.Unavailable(regionId, Reason.NO_WORLDGUARD)
        val world = display.pos1.world ?: return State.Unavailable(regionId, Reason.NO_WORLD)
        return try {
            when (val read = WorldGuardShapes.read(world, regionId)) {
                is RegionShape -> State.Active(regionId, read, fade, load)
                is Reason -> State.Unavailable(regionId, read)
                else -> State.Unavailable(regionId, Reason.UNSUPPORTED_SHAPE)
            }
        } catch (e: Throwable) {
            // WorldGuard installed but not enabled, or an API we do not know
            logger.warn("Could not read WorldGuard region {} for display {}: {}", regionId, display.id, e.toString())
            State.Unavailable(regionId, Reason.NO_WORLDGUARD)
        }
    }

    /** One line for logs: `none`, `cuboid spawn`, `unavailable spawn (NOT_FOUND)`. */
    fun summary(state: State): String = when (state) {
        State.None -> "none"
        is State.Active -> "${shapeName(state.shape)} ${state.regionId}"
        is State.Unavailable -> "unavailable ${state.regionId} (${state.reason})"
    }

    /** `cuboid x y z .. x y z` or `polygon (n corners) y a..b`, for admins. */
    fun describeShape(shape: RegionShape): String = when (shape) {
        is RegionShape.Cuboid -> "cuboid ${shape.minX} ${shape.minY} ${shape.minZ} .. ${shape.maxX} ${shape.maxY} ${shape.maxZ}"
        is RegionShape.Polygon -> "polygon, ${shape.xs.size} corners, y ${shape.minY}..${shape.maxY}"
    }

    private fun shapeName(shape: RegionShape): String = when (shape) {
        is RegionShape.Cuboid -> "cuboid"
        is RegionShape.Polygon -> "polygon"
    }
}

/** The only place `WorldGuard` classes are touched; call only when [WorldGuardRegions.isAvailable]. */
@PaperOnly
private object WorldGuardShapes {
    /** The shape of region [id] in [world], or the [Reason] it has none. */
    fun read(world: World, id: String): Any {
        val container = com.sk89q.worldguard.WorldGuard.getInstance().platform.regionContainer
        val manager = container[com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world)] ?: return Reason.NO_WORLD
        val region = manager.getRegion(id) ?: return Reason.NOT_FOUND
        val min = region.minimumPoint
        val max = region.maximumPoint
        return when (region) {
            is com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion ->
                RegionShape.Cuboid(min.blockX, min.blockY, min.blockZ, max.blockX, max.blockY, max.blockZ)

            is com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion -> {
                val points = region.points
                if (points.size < 3 || points.size > DisplayRegionShape.MAX_CORNERS) return Reason.UNSUPPORTED_SHAPE
                RegionShape.Polygon(points.map { it.blockX }, points.map { it.blockZ }, min.blockY, max.blockY)
            }

            else -> Reason.UNSUPPORTED_SHAPE
        }
    }
}
