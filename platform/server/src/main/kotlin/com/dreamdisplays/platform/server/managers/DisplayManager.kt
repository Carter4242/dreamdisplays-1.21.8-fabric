package com.dreamdisplays.platform.server.managers

import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.core.command.TargetMatch
import com.dreamdisplays.core.display.region.RegionVisibility
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.shared.capability.ClientFeature
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.sync.DisplayFollowers
import com.dreamdisplays.core.sync.EmptyDisplays
import com.dreamdisplays.platform.server.PaperServer.Companion.config
import com.dreamdisplays.platform.server.PaperServer.Companion.getInstance
import com.dreamdisplays.platform.server.PaperSurfaces
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.VanillaSurfaces
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.server.baseMaterial
import com.dreamdisplays.platform.server.baseMaterialId
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.datatypes.selection.PaperSelectionData
import com.dreamdisplays.platform.server.datatypes.selection.VanillaSelectionData
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.Scheduler.runSync
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.PipPinManager
import com.dreamdisplays.platform.server.playback.ScheduledPlaybackManager
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.platform.server.utils.ReporterUtil
import com.dreamdisplays.platform.server.utils.net.EmptyDisplayDescriptions
import com.dreamdisplays.platform.server.utils.net.PacketUtil
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries
//?} else
/*import net.minecraft.core.Registry as BuiltInRegistries*/
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.Bukkit.getOfflinePlayer
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Manages all displays on the server. Handles registration, deletion, overlap detection,
 * receiver lookup, and report rate-limiting.
 */
@NullMarked
object DisplayManager {
    private val displays: MutableMap<UUID, DisplayData> = ConcurrentHashMap()
    private val reportThrottle = ReportThrottle()
    private val proximityIndex = DisplayProximityIndex()

    /**
     * Who got each display at the last display update (`Paper`; `Folia` keeps this in [proximityIndex]): a released
     * client walking out of a region display's region is told to drop it, and keeps it for a block of slack meanwhile.
     */
    private val lastReceivers: MutableMap<UUID, Set<UUID>> = ConcurrentHashMap()

    /** Returns the display registered under [id], or null if none exists. */
    @JvmStatic
    fun getDisplayData(id: UUID?): DisplayData? = displays[id]

    /** Returns a snapshot list of all currently registered displays. */
    fun getDisplays(): List<DisplayData> = displays.values.toList()

    /** Number of displays currently owned by [ownerId], across every registered display. */
    fun countOwnedBy(ownerId: UUID): Int = displays.values.count { it.ownerId == ownerId }

    /** Resolves [idOrPrefix] against every registered display: an exact id match first, then an exact case-insensitive name match. */
    fun resolveByIdOrPrefix(idOrPrefix: String): DisplayData? {
        runCatching { UUID.fromString(idOrPrefix) }.getOrNull()?.let { exact ->
            getDisplayData(exact)?.let { return it }
        }
        displays.values.firstOrNull { it.name.equals(idOrPrefix, ignoreCase = true) }?.let { return it }
        if (idOrPrefix.length < 4) return null
        val matches = displays.values.filter { it.id.toString().startsWith(idOrPrefix, ignoreCase = true) }
        return matches.singleOrNull()
    }

    /**
     * True when another display (not [excludeId]) already carries [name], case-insensitively, or [name] would hide
     * another display's short id ([TargetMatch.nameShadowsId]).
     */
    fun isNameTaken(name: String, excludeId: UUID): Boolean =
        displays.values.any {
            it.id != excludeId && (it.name.equals(name, ignoreCase = true) || TargetMatch.nameShadowsId(name, it.id))
        }

    /** Bulk-registers displays loaded from storage without sending any updates. */
    fun register(list: List<DisplayData>) {
        list.forEach { displays[it.id] = it }
    }

    /** Removes the display referenced by [id], if it exists. */
    @PaperOnly
    @JvmStatic
    fun delete(id: UUID) {
        val data = displays[id] as? PaperDisplayData ?: return
        delete(data)
    }

    private fun isInRangeImpl(
        x: Int, y: Int, z: Int,
        minX: Int, minY: Int, minZ: Int,
        maxX: Int, maxY: Int, maxZ: Int,
        maxRender: Double,
    ): Boolean {
        val dx = x - x.coerceIn(minX, maxX)
        val dy = y - y.coerceIn(minY, maxY)
        val dz = z - z.coerceIn(minZ, maxZ)
        return dx * dx + dy * dy + dz * dz <= maxRender * maxRender
    }

    private fun removeDisplays(toRemove: List<DisplayData>, delete: (DisplayData) -> Unit): List<UUID> {
        return toRemove.map { display ->
            displays.remove(display.id)
            proximityIndex.forgetDisplay(display.id)
            lastReceivers.remove(display.id)
            TimelineManager.remove(display.id)
            FullscreenBroadcastManager.onDisplayRemoved(display.id)
            PipPinManager.onDisplayRemoved(display.id)
            QueueManager.onDisplayRemoved(display.id)
            com.dreamdisplays.platform.server.playback.DisplayActivity.forget(display.id)
            ScheduledPlaybackManager.onDisplayRemoved(display.id)
            delete(display)
            ServerApiHost.current?.removed(display)
            display.id
        }
    }

    /** Returns the first display whose bounding box contains [location], or null. */
    @PaperOnly
    fun isContains(location: Location): PaperDisplayData? {
        return displays.values.filterIsInstance<PaperDisplayData>().firstOrNull { d ->
            d.pos1.world == location.world && d.box.contains(location.toVector())
        }
    }

    /** Returns true if the selection [data] intersects any existing display. */
    @PaperOnly
    fun isOverlaps(data: PaperSelectionData): Boolean {
        val pos1 = data.pos1 ?: return false
        val pos2 = data.pos2 ?: return false
        val selWorld = pos1.world
        val region = RegionUtil.calculateRegion(pos1, pos2)
        val box = BoundingBox(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
        return displays.values.filterIsInstance<PaperDisplayData>().any { d ->
            d.pos1.world == selWorld && box.overlaps(d.box)
        }
    }

    /** Registers a new display, persists it, and broadcasts an update to nearby players. */
    @PaperOnly
    fun register(data: PaperDisplayData) {
        displays[data.id] = data
        runAsync { getInstance().storage.saveDisplay(data) }
        broadcastUpdate(data)
        com.dreamdisplays.platform.server.playback.DisplayActivity.record(data, com.dreamdisplays.core.activity.ActivityKind.CREATED)
        ServerApiHost.current?.created(data)
    }

    /** Returns the players currently in range of [display] in its world. */
    @PaperOnly
    fun getReceivers(display: PaperDisplayData): List<Player> =
        display.pos1.world?.players?.filter { it.isInRange(display) } ?: []

    /** True if [player] is currently within render range of [display] — the same predicate that decides who receives its frames. */
    @PaperOnly
    fun isPlayerInRange(player: Player, display: PaperDisplayData): Boolean = player.isInRange(display)

    @PaperOnly
    private fun Location.isInRange(display: PaperDisplayData): Boolean =
        isInRangeImpl(
            blockX, blockY, blockZ,
            display.box.minX.toInt(), display.box.minY.toInt(), display.box.minZ.toInt(),
            display.box.maxX.toInt(), display.box.maxY.toInt(), display.box.maxZ.toInt(),
            (minOf(
                world?.viewDistance ?: Bukkit.getViewDistance(),
                world?.simulationDistance ?: Bukkit.getSimulationDistance(),
            ) * 16).toDouble(),
        )

    /**
     * In range of [display] (its world, the view / simulation distance) and, for a display tied to a region, admitted by
     * it ([admitsByRegion]): everything that asks who gets a display (descriptions, timelines, start holds, queue
     * readiness, the `Folia` proximity index) goes through here.
     */
    @PaperOnly
    private fun Player.isInRange(display: PaperDisplayData): Boolean {
        if (display.pos1.world != world) return false
        val at = location
        return at.isInRange(display) && admitsByRegion(this, at, display)
    }

    /**
     * Whether [display]'s region (`visibility.region`) lets [player], standing at [at], have it: always without one;
     * never while it cannot be resolved; else near it for a client with `region_fade`, inside it for a released one
     * ([RegionVisibility.admits]). A karaoke singer on the display keeps it wherever they stand.
     */
    @PaperOnly
    private fun admitsByRegion(player: Player, at: Location, display: PaperDisplayData): Boolean {
        val state = DisplayRegions.stateOf(display)
        if (state === RegionVisibility.State.None) return true
        if (com.dreamdisplays.platform.server.karaoke.KaraokeManager.isSinging(display.id, player.uniqueId)) return true
        return RegionVisibility.admits(
            state, isRegionCapable(player.uniqueId), at.x, at.y, at.z, wasReceiving(display.id, player.uniqueId),
        )
    }

    /** Whether [playerId]'s client follows a display's region itself (fade, load distance): `ClientFeature.REGION_FADE`. */
    fun isRegionCapable(playerId: UUID): Boolean =
        V2PlayerTracker.helloOf(playerId)?.hasFeature(ClientFeature.REGION_FADE) == true

    /** Whether [player] can hear [display] at all where they stand: always without a region, else see [RegionVisibility.hears]. */
    @PaperOnly
    fun hearsDisplay(player: Player, display: PaperDisplayData): Boolean {
        val state = DisplayRegions.stateOf(display)
        if (state === RegionVisibility.State.None) return true
        val at = player.location
        return RegionVisibility.hears(state, isRegionCapable(player.uniqueId), at.x, at.y, at.z)
    }

    private fun wasReceiving(displayId: UUID, playerId: UUID): Boolean =
        if (PlatformUtil.isFolia) proximityIndex.isTracked(displayId, playerId)
        else lastReceivers[displayId]?.contains(playerId) == true

    /**
     * After a display update: the players who stopped getting a display tied to a region ([left]: walked out of it, or
     * of its load range) are told to drop it, so a released client loses it at the region's edge and none keeps a copy
     * it could bring back on its own. Not those following it from afar (fullscreen, preparation, PiP pin).
     */
    @PaperOnly
    private fun dropForLeavers(display: PaperDisplayData, left: Collection<UUID>, send: (UUID) -> Unit) {
        if (left.isEmpty()) return
        val far = FullscreenBroadcastManager.timelineFollowers(display.id).toSet() + farPinners(display.id)
        for (playerId in left) if (playerId !in far) send(playerId)
    }

    /** Removes [playerId] from the cached `Folia` proximity index. */
    @PaperOnly
    fun forgetNearbyPlayer(playerId: UUID) = proximityIndex.forgetPlayer(playerId)

    /** Cached nearby player ids for `Folia` global coordinators that cannot read entity locations directly. */
    @PaperOnly
    fun getTrackedNearbyPlayerIds(display: PaperDisplayData): List<UUID> =
        proximityIndex.trackedNearbyPlayerIds(display.id)

    /** Sends a `DisplayInfo` packet describing [display] to the given [players]. */
    @PaperOnly
    fun sendUpdate(display: PaperDisplayData, players: List<Player>, forced: Boolean = false) {
        if (players.isEmpty()) return
        PacketUtil.sendDisplayInfo(
            players,
            display.id, display.ownerId, display.box.min, display.width, display.height,
            display.url, display.lang, display.facing, display.isSync, display.isLocked,
            display.access, display.mode, display.qualityCap, display.rotation,
            virtual = display.virtual, forced = forced,
            scheduledStartEpochMillis = display.scheduledStart?.toEpochMilliseconds() ?: 0,
            scheduledAction = display.scheduledAction?.wire ?: -1,
            inRegion = WorldGuardRegions.isProtectedTerritory(display.pos1),
            isRegionMember = { WorldGuardRegions.isRegionMember(it, display.pos1) },
            depth = display.depth, conforming = display.conforming,
            customData = DisplayPolicies.wireCustomData(display),
            mediaGen = display.mediaGen,
            // Virtual (URL-only broadcast) displays stand nowhere
            world = if (display.virtual) "" else display.pos1.world?.key?.toString().orEmpty(),
            region = DisplayRegions.wireShape(display),
            // An empty display goes to clients before protocol 12 sparingly ([EmptyDisplayDescriptions])
            sendsTo = { player, info -> EmptyDisplayDescriptions.sendsTo(player.uniqueId, info) },
        )
    }

    /** Forgets what the proximity index knew about [display]: it is somewhere else now. */
    @PaperOnly
    fun onMoved(display: PaperDisplayData) {
        proximityIndex.forgetDisplay(display.id)
    }

    /** Broadcasts [display]'s current info through the appropriate `Paper` / `Folia` player scheduler path. */
    @PaperOnly
    fun broadcastUpdate(display: PaperDisplayData) {
        if (PlatformUtil.isFolia) {
            Scheduler.forEachTrackedPlayer { player ->
                if (player.isInRange(display)) sendUpdate(display, [player])
            }
        } else {
            sendUpdate(display, getReceivers(display))
        }
        // Far followers (fullscreen viewers, prepared clients) play it too: a new video or mode must reach them
        val targets = DisplayFollowers.infoTargets(
            com.dreamdisplays.platform.server.playback.PaperPlaybackTransport.nearbyPlayerIds(display), FullscreenBroadcastManager.timelineFollowers(display.id), farPinners(display.id),
        )
        for ((playerId, forced) in targets) com.dreamdisplays.platform.server.playback.PaperPlaybackTransport.sendDisplayInfo(playerId, display, forced)
    }

    /**
     * [display]'s video was cleared: a client older than [EmptyDisplays.CLEAR_PROTOCOL] ignores the empty URL and plays
     * the cleared video on, so each one that gets its updates (in range, far followers, pinners) is told to drop it
     * first. Call just before [broadcastUpdate], whose description brings it back with no video.
     */
    @PaperOnly
    fun dropForPreClearClients(display: PaperDisplayData) {
        val preClear = { id: UUID -> EmptyDisplays.dropBeforeClear(V2PlayerTracker.helloOf(id)?.protocolVersion ?: 0) }
        if (PlatformUtil.isFolia) {
            Scheduler.forEachTrackedPlayer { player ->
                if (preClear(player.uniqueId) && player.isInRange(display)) PacketUtil.sendDelete([player], display.id)
            }
        } else {
            PacketUtil.sendDelete(getReceivers(display).filter { preClear(it.uniqueId) }, display.id)
        }
        val far = DisplayFollowers.farReceivers(
            com.dreamdisplays.platform.server.playback.PaperPlaybackTransport.nearbyPlayerIds(display), FullscreenBroadcastManager.timelineFollowers(display.id), farPinners(display.id),
        )
        for (playerId in far) {
            if (preClear(playerId)) com.dreamdisplays.platform.server.playback.PaperPlaybackTransport.sendTo(playerId, DisplayDelete(display.id))
        }
    }

    /** Online players with [displayId] pinned to PiP. */
    private fun farPinners(displayId: UUID): Collection<UUID> = PipPinManager.pinnersOf(displayId)

    /** Broadcasts a display delete packet through the appropriate `Paper` / `Folia` player scheduler path. */
    @PaperOnly
    fun broadcastDelete(display: PaperDisplayData) {
        if (PlatformUtil.isFolia) {
            Scheduler.forEachTrackedPlayer { player ->
                if (player.isInRange(display)) PacketUtil.sendDelete([player], display.id)
            }
        } else {
            PacketUtil.sendDelete(getReceivers(display), display.id)
        }
        val far = DisplayFollowers.farReceivers(
            com.dreamdisplays.platform.server.playback.PaperPlaybackTransport.nearbyPlayerIds(display), FullscreenBroadcastManager.timelineFollowers(display.id), farPinners(display.id),
        )
        for (playerId in far) com.dreamdisplays.platform.server.playback.PaperPlaybackTransport.sendTo(playerId, DisplayDelete(display.id))
    }

    /** Refreshes all displays visible to every tracked player through each player's entity scheduler. */
    @PaperOnly
    fun updateAllDisplaysForTrackedPlayers() {
        val papers = displays.values.filterIsInstance<PaperDisplayData>()
        DisplayRegions.refreshStale(papers)
        Scheduler.forEachTrackedPlayer { player ->
            val visible = papers.filter { player.isInRange(it) }
            val ids = visible.mapTo(mutableSetOf()) { it.id }
            val previous = proximityIndex.update(player.uniqueId, ids)
                // First update since a (re)load: anything in plain range may still be on the client
                ?: papers.filter { it.pos1.world == player.world && player.location.isInRange(it) }.mapTo(HashSet()) { it.id }
            visible.forEach { display -> sendUpdate(display, [player]) }
            for (id in previous - ids) {
                val display = displays[id] as? PaperDisplayData ?: continue
                if (DisplayRegions.stateOf(display) === RegionVisibility.State.None) continue
                dropForLeavers(display, [player.uniqueId]) { PacketUtil.sendDelete([player], id) }
            }
        }
    }

    /** Sends a v2 packet to tracked nearby v2 players, evaluating each location on that player's entity thread. */
    @PaperOnly
    fun sendV2ToTrackedNearbyPlayers(display: PaperDisplayData, packet: DreamPacket) {
        Scheduler.forEachTrackedPlayer { player ->
            if (V2PlayerTracker.isV2(player.uniqueId) && player.isInRange(display)) {
                PaperV2Networking.send([player], packet)
            }
        }
    }

    /** Sends a refresh packet for every display to in-range players in their respective worlds. */
    @PaperOnly
    fun updateAllDisplays() {
        val papers = displays.values.filterIsInstance<PaperDisplayData>()
        DisplayRegions.refreshStale(papers)
        val playersByWorld = papers.mapNotNull { it.pos1.world }.distinct()
            .associateWith { it.players.toMutableList() }

        papers.forEach { display ->
            val world = display.pos1.world ?: return@forEach
            val worldPlayers = playersByWorld[world] ?: mutableListOf()
            val receivers = worldPlayers.filter { it.isInRange(display) }
            sendUpdate(display, receivers)
            val now = receivers.mapTo(HashSet()) { it.uniqueId }
            val regional = DisplayRegions.stateOf(display) !== RegionVisibility.State.None
            val before = lastReceivers.put(display.id, now)
                // First update since a (re)load: anyone in plain range may still have it
                ?: if (regional) worldPlayers.filter { it.location.isInRange(display) }.mapTo(HashSet()) { it.uniqueId } else emptySet()
            if (regional) {
                dropForLeavers(display, before - now) { id ->
                    Bukkit.getPlayer(id)?.let { PacketUtil.sendDelete([it], display.id) }
                }
            }
        }
    }

    /** Removes [displayData] from storage and the registry and notifies nearby clients. */
    @PaperOnly
    fun delete(displayData: PaperDisplayData) {
        com.dreamdisplays.platform.server.playback.DisplayActivity.record(displayData, com.dreamdisplays.core.activity.ActivityKind.DELETED)
        runAsync { getInstance().storage.deleteDisplay(displayData) }
        broadcastDelete(displayData)
        TimelineManager.remove(displayData.id)
        FullscreenBroadcastManager.onDisplayRemoved(displayData.id)
        PipPinManager.onDisplayRemoved(displayData.id)
        QueueManager.onDisplayRemoved(displayData.id)
        com.dreamdisplays.platform.server.utils.net.ScreenShareActions.onDisplayRemoved(displayData.id)
        com.dreamdisplays.platform.server.utils.net.MenuPanels.onDisplayRemoved(displayData.id)
        displays.remove(displayData.id)
        proximityIndex.forgetDisplay(displayData.id)
        lastReceivers.remove(displayData.id)
        DisplayRegions.forget(displayData.id)
        ServerApiHost.current?.removed(displayData)
    }

    /**
     * Posts a report about display [id] to the configured webhook, respecting per-display cooldown
     * and informing [player] about the outcome.
     */
    @PaperOnly
    @JvmStatic
    fun report(id: UUID, player: Player) {
        val displayData = displays[id] as? PaperDisplayData ?: return

        if (reportThrottle.isThrottled(id, player.uniqueId, config.settings.reportCooldown)) {
            ServerMessage.REPORT_TOO_QUICKLY.sendTo(player)
            return
        }

        runAsync {
            if (config.settings.webhookUrl.isEmpty()) return@runAsync

            runCatching {
                ReporterUtil.sendReport(
                    displayData.pos1, displayData.url, displayData.id, player,
                    config.settings.webhookUrl, getOfflinePlayer(displayData.ownerId).name,
                )
            }.onSuccess {
                runSync { ServerMessage.REPORT_SENT.sendTo(player) }
            }.onFailure { e ->
                getInstance().logger.warning("Exception while sending report: ${e.message}")
                runSync { ServerMessage.REPORT_FAILED.sendTo(player) }
            }
        }
    }

    /** Invokes [saveDisplay] for every currently registered display (used by storage flush). */
    @PaperOnly
    fun save(saveDisplay: Consumer<PaperDisplayData>) {
        displays.values.filterIsInstance<PaperDisplayData>().forEach(saveDisplay)
    }

    /**
     * Scans every display's bounding box for the configured base material; displays with none are removed from disk and
     * registry. Each display's chunks are loaded asynchronously and it is checked once they are in (on the main thread),
     * so the first join after a restart does not load every display's chunks in one tick.
     */
    @PaperOnly
    fun validateDisplaysAndCleanup() {
        val surface = PaperSurfaces.of(config.settings.baseMaterial)
        displays.values.filterIsInstance<PaperDisplayData>().forEach { display ->
            // An unloaded world (e.g., a Multiverse world that loads later) is not an invalid display:
            // skip it this pass instead of wiping it from the database.
            val world = display.pos1.world
            if (world == null) {
                getInstance().logger.warning("Skipping validation for display ${display.id}: world is not loaded.")
                return@forEach
            }
            val box = display.box
            val chunks = ArrayList<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>>()
            for (cx in (box.minX.toInt() shr 4)..((box.maxX.toInt() - 1) shr 4)) {
                for (cz in (box.minZ.toInt() shr 4)..((box.maxZ.toInt() - 1) shr 4)) chunks += world.getChunkAtAsync(cx, cz)
            }
            java.util.concurrent.CompletableFuture.allOf(*chunks.toTypedArray()).whenComplete { _, error ->
                val check = Runnable {
                    if (error != null) {
                        getInstance().logger.warning("Skipping validation for display ${display.id}: its chunks did not load ($error).")
                    } else if (displays[display.id] === display && !hasBaseMaterial(display, world, surface)) {
                        val removed = removeDisplays(listOf(display)) { runAsync { getInstance().storage.deleteDisplay(it as PaperDisplayData) } }
                        PacketUtil.sendClearCache(Bukkit.getOnlinePlayers().toList(), removed)
                    }
                }
                if (Bukkit.isPrimaryThread()) check.run() else runSync { check.run() }
            }
        }
    }

    /** True when [display]'s box (chunks loaded) holds a block [surface] accepts. */
    @PaperOnly
    private fun hasBaseMaterial(display: PaperDisplayData, world: org.bukkit.World, surface: com.dreamdisplays.shared.geometry.DisplaySurface): Boolean {
        val box = display.box
        for (x in box.minX.toInt() until box.maxX.toInt()) {
            for (y in box.minY.toInt() until box.maxY.toInt()) {
                for (z in box.minZ.toInt() until box.maxZ.toInt()) {
                    val type = world.getBlockAt(x, y, z).type
                    if (!type.isAir && surface.accepts(type.name)) return true
                }
            }
        }
        return false
    }

    /** Returns the first display whose bounding box contains [blockPos] in [worldKey]. */
    fun isContains(worldKey: String, blockPos: BlockPos): VanillaDisplayData? {
        return displays.values.filterIsInstance<VanillaDisplayData>().firstOrNull { d ->
            d.worldKey == worldKey &&
                    d.box.contains(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        }
    }

    /** Returns true if the selection [sel] intersects any existing display. */
    fun isOverlaps(sel: VanillaSelectionData): Boolean {
        val selBox = sel.selectionBox() ?: return false
        val wk = sel.worldKey ?: return false
        return displays.values.filterIsInstance<VanillaDisplayData>().any { d ->
            d.worldKey == wk && d.box.intersects(selBox)
        }
    }

    /**
     * Forgets every display: an integrated server stopped, and the next world opened in the same game has its own (its
     * displays would otherwise show up in that world and be saved into its database). Call after the final save.
     */
    fun clearAll() {
        displays.clear()
        proximityIndex.clear()
        lastReceivers.clear()
    }

    /** Registers a new display. Caller is responsible for broadcasting. */
    fun register(data: VanillaDisplayData) {
        displays[data.id] = data
        ServerApiHost.current?.created(data)
    }

    /** Returns the players currently in range of [display] in its world. */
    fun getReceivers(display: VanillaDisplayData, server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filter { p ->
            RegionUtil.getPlayerLevelKey(p) == display.worldKey &&
                    p.blockPosition().isInRange(display)
        }
    }

    /** True if [player] is currently within render range of [display] — the same predicate that decides who receives its frames. */
    fun isPlayerInRange(player: ServerPlayer, display: VanillaDisplayData): Boolean =
        RegionUtil.getPlayerLevelKey(player) == display.worldKey && player.blockPosition().isInRange(display)

    private fun BlockPos.isInRange(display: VanillaDisplayData): Boolean =
        isInRangeImpl(
            x, y, z,
            display.minX, display.minY, display.minZ,
            display.maxX, display.maxY, display.maxZ,
            (VanillaServerState.server?.playerList?.let { minOf(it.viewDistance, it.simulationDistance) } ?: 10) * 16.0,
        )

    /** Sends a `DisplayInfo` packet describing [display] to the given [players]. */
    fun sendUpdate(display: VanillaDisplayData, players: List<ServerPlayer>) {
        VanillaPacketUtil.sendDisplayInfo(players, display)
    }

    /** Sends a refresh packet for every display to in-range players. */
    fun updateAllDisplays(server: MinecraftServer) {
        displays.values.filterIsInstance<VanillaDisplayData>().forEach { display ->
            val receivers = getReceivers(display, server)
            if (receivers.isNotEmpty()) sendUpdate(display, receivers)
        }
    }

    /** Removes [data] from storage and the registry. The JDBC delete runs off-thread on [ServerCoroutines.io]. */
    fun delete(data: VanillaDisplayData) {
        val receivers = VanillaServerState.server?.let { getReceivers(data, it) }.orEmpty()
        displays.remove(data.id)
        TimelineManager.remove(data.id)
        FullscreenBroadcastManager.onDisplayRemoved(data.id)
        PipPinManager.onDisplayRemoved(data.id)
        QueueManager.onDisplayRemoved(data.id)
        com.dreamdisplays.platform.server.playback.DisplayActivity.forget(data.id)
        ServerCoroutines.io.launch { VanillaServerState.storage?.deleteDisplay(data) }
        if (receivers.isNotEmpty()) VanillaPacketUtil.sendDelete(receivers, data.id)
        ServerApiHost.current?.removed(data)
    }

    /**
     * Posts a report about display [id] to the configured webhook, respecting per-display cooldown
     * and informing [player] about the outcome.
     */
    fun report(id: UUID, player: ServerPlayer, server: MinecraftServer) {
        val displayData = displays[id] as? VanillaDisplayData ?: return
        val cfg = VanillaServerState.config
        if (reportThrottle.isThrottled(id, player.uuid, cfg.settings.reportCooldown)) {
            ServerMessage.REPORT_TOO_QUICKLY.sendTo(player)
            return
        }
        if (cfg.settings.webhookUrl.isEmpty()) return

        val ownerName = server.playerList.players.find { it.uuid == displayData.ownerId }?.name?.string ?: "Unknown"
        val locationStr =
            "${displayData.worldKey} (x=${displayData.minX}, y=${displayData.minY}, z=${displayData.minZ})"

        ServerCoroutines.io.launch {
            runCatching {
                ReporterUtil.sendReport(
                    locationStr,
                    displayData.url,
                    displayData.id,
                    player.name.string,
                    ownerName,
                    cfg.settings.webhookUrl,
                )
                server.execute { ServerMessage.REPORT_SENT.sendTo(player) }
            }.onFailure {
                server.execute { ServerMessage.REPORT_FAILED.sendTo(player) }
            }
        }
    }

    /** Invokes [saveDisplay] for every currently registered display (used by storage flush). */
    fun save(saveDisplay: (VanillaDisplayData) -> Unit) {
        displays.values.filterIsInstance<VanillaDisplayData>().forEach(saveDisplay)
    }

    /** Scans every display's bounding box for the configured base material; displays with none are removed from disk and registry. */
    fun validateDisplaysAndCleanup(server: MinecraftServer): List<UUID> {
        val cfg = VanillaServerState.config
        val surface = VanillaSurfaces.of(cfg.settings.baseMaterialId)
        val invalidDisplays = mutableListOf<VanillaDisplayData>()

        displays.values.filterIsInstance<VanillaDisplayData>().forEach { display ->
            // An unloaded dimension is not an invalid display: skip it this pass instead of wiping
            // it from the database.
            val level = RegionUtil.getLevelByKey(server, display.worldKey) ?: run {
                VanillaServerState.logger.warn("Skipping validation for display ${display.id}: dimension '${display.worldKey}' is not loaded.")
                return@forEach
            }
            var hasBaseMaterial = false
            outerLoop@ for (x in display.minX..display.maxX) {
                for (y in display.minY..display.maxY) {
                    for (z in display.minZ..display.maxZ) {
                        val state = level.getBlockState(BlockPos(x, y, z))
                        val regName = BuiltInRegistries.BLOCK.getKey(state.block).toString()
                        if (!state.isAir && surface.accepts(regName)) {
                            hasBaseMaterial = true
                            break@outerLoop
                        }
                    }
                }
            }
            if (!hasBaseMaterial) invalidDisplays.add(display)
        }

        val removed = removeDisplays(invalidDisplays) { display ->
            ServerCoroutines.io.launch { VanillaServerState.storage?.deleteDisplay(display as VanillaDisplayData) }
        }
        if (removed.isNotEmpty()) {
            VanillaPacketUtil.sendClearCache(server.playerList.players, removed)
        }
        return removed
    }
}
