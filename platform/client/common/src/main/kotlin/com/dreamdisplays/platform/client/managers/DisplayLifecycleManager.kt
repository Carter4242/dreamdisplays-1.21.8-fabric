package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.displays.ServerMediaGens
import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.storage.DisplayData
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.services.DisplayStorage
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayDimensions
import com.dreamdisplays.platform.client.displays.DisplayPolicyCache
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.DisplayLifecycleManager.MAX_DISPLAY_BLOCKS
import com.dreamdisplays.api.display.geometry.DisplayGeometry
import com.dreamdisplays.platform.client.displays.PlacedDisplays
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.platform.client.displays.acceptedCustomData
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.util.FacingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import org.joml.Vector3i
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Handles client-side display creation, restoration, and render-distance lifecycle.
 */
object DisplayLifecycleManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val MAX_DISPLAY_BLOCKS = 256
    private const val BUDGET_HYSTERESIS_BLOCKS = 4

    /** Creates or updates a display from a server [DisplayInfo] packet, honoring render distance and size limits. */
    fun handleInfoPacket(packet: DisplayInfo) {
        // A description this client can't show is dropped before its media generation is recorded: recorded, a later
        // sync of the same generation would no longer ask for a description, and the display would never appear
        if (packet.facing !in FacingUtil.entries.indices) {
            logger.warn("Ignoring display ${packet.id}: unknown facing ${packet.facing}.")
            return
        }
        if (!isValidDisplaySize(packet.width, packet.height, packet.depth)) {
            logger.warn("Ignoring display ${packet.id}: invalid size ${packet.width} x ${packet.height} x ${packet.depth}.")
            return
        }
        // Known even while displays are off: its timelines must not look like ones for a display never described
        // A display that got a new video: what this client said about the old one is void on the server
        val newMedia = ServerMediaGens.onInfo(packet)
        if (newMedia) DisplayWarmup.reReport(packet.id)
        if (!ClientStateManager.displaysEnabled) return

        val policy = DisplayPolicyCache.update(packet.id, acceptedCustomData(packet))
        lastInfo[packet.id] = packet
        // First sight of a display seeds the player's volume for it from the server's audio.startVolume
        ClientSettingsStore.getSettings(packet.id, DisplayScreen.defaultVolume(policy))

        DisplayRegistry.screens[packet.id]?.let {
            DisplayRegistry.markReconfirmed(packet.id)
            it.dimensionKey = DisplayDimensions.learnedKey(
                it.dimensionKey, packet.world, packet.forced, packet.virtual, currentDimensionKey(),
            )
            it.updateData(packet)
            if (newMedia) it.onMediaGenChanged()
            DisplayRegistry.recordScreen(it)
            return
        }

        val facing = FacingUtil.fromPacket(packet.facing.toByte())
        val mode = if (packet.mode == PlaybackMode.LOCAL.wire && packet.isSync) {
            PlaybackMode.SYNCED
        } else {
            PlaybackMode.fromWire(packet.mode)
        }
        val renderDistance = DisplayPolicyCache.renderDistanceFor(policy)

        // Switched off by the server: remember it so it comes back as soon as the server allows it again (not a forced
        // description without a world, which may come from another one)
        if (!ServerOverrides.displayEnabled(policy)) {
            if (!packet.virtual && (!packet.forced || packet.world.isNotEmpty())) {
                cacheUnloadedDisplay(packet, facing, mode, worldOf(packet))
            }
            return
        }

        if (!packet.forced && !packet.virtual) {
            Minecraft.getInstance().player?.let { player ->
                val dist = distanceToScreen(
                    packet.id, packet.x, packet.y, packet.z,
                    packet.width, packet.height, facing.toDisplayFacing(),
                    player.blockPosition(), packet.depth,
                )
                if (dist > renderDistance || !fitsBudget(dist, player.blockPosition())) {
                    cacheUnloadedDisplay(packet, facing, mode, worldOf(packet))
                    return
                }
            }
        }

        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(packet.url))
        DisplayRegistry.unloadedScreens.remove(packet.id)

        createScreen(
            packet.id, packet.ownerId, Vector3i(packet.x, packet.y, packet.z), facing,
            packet.width, packet.height, packet.url, packet.lang,
            mode, packet.qualityCap, DisplayRotation.fromQuarterTurns(packet.rotation),
            DisplayDimensions.keyForNewScreen(packet.world, packet.forced, packet.virtual, currentDimensionKey()),
            packet.depth, packet.conforming, info = packet,
        )
    }

    /**
     * The last description the server sent per display, so a display restored from the distance cache starts with its
     * policy, lock, access and schedule instead of the client defaults. Cleared per server.
     */
    private val lastInfo = java.util.concurrent.ConcurrentHashMap<UUID, DisplayInfo>()

    /** Forgets [displayId]'s last description (deleted or evicted). */
    fun forgetInfo(displayId: UUID) {
        lastInfo.remove(displayId)
    }

    /** Forgets every display description (leaving a server). */
    fun clearInfo() = lastInfo.clear()

    private fun cacheUnloadedDisplay(
        packet: DisplayInfo, facing: FacingUtil, mode: PlaybackMode, dimensionKey: String,
    ) {
        val settings = ClientSettingsStore.getSettings(packet.id, DisplayScreen.defaultVolume())
        DisplayRegistry.unloadedScreens[packet.id] = DisplayData(
            uuid = packet.id,
            x = packet.x, y = packet.y, z = packet.z,
            facing = facing.toDisplayFacing(),
            width = packet.width, height = packet.height,
            depth = packet.depth, conforming = packet.conforming,
            videoUrl = packet.url, lang = packet.lang,
            volume = settings.volume, quality = settings.quality, brightness = settings.brightness,
            muted = settings.muted, mode = mode, ownerUuid = packet.ownerId,
            currentTimeNanos = settings.savedTimeNanos,
            rotation = DisplayRotation.fromQuarterTurns(packet.rotation).quarterTurns,
            qualityCap = packet.qualityCap,
            dimensionKey = dimensionKey,
        )
    }

    /** Builds and registers a new [DisplayScreen], applying saved render distance and loading the video. */
    fun createScreen(
        uuid: UUID, ownerUuid: UUID, pos: Vector3i, facingUtil: FacingUtil,
        width: Int, height: Int, code: String, lang: String,
        mode: PlaybackMode, qualityCap: Int, rotation: DisplayRotation = DisplayRotation.NONE,
        dimensionKey: String = currentDimensionKey(),
        depth: Int = 1, conforming: Boolean = false,
        info: DisplayInfo? = null,
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toDisplayFacing(),
            width, height, mode, qualityCap, rotation, dimensionKey,
            depth.coerceAtLeast(1), conforming,
        )

        displayScreen.createTexture()
        DisplayRegistry.registerScreen(displayScreen)
        // From a server description: applied whole (policy, lock, access...) before the video loads, and it records
        // the packet language, so the next periodic description does not restart the video
        if (info != null) displayScreen.updateData(info)
        else if (code != "") displayScreen.loadVideo(code, lang)

        // Not for a display only loaded ahead of a broadcast: that one stays out of sight until the broadcast
        if (ClientSettingsStore.getSettings(uuid, DisplayScreen.defaultVolume()).pipOpen &&
            !FullscreenPrepareController.isPrepared(uuid)
        ) {
            displayScreen.activatePipMode()
        }
    }

    fun restoreVisibleUnloadedScreens(playerPos: BlockPos) {
        val dimensionKey = currentDimensionKey()
        DisplayRegistry.unloadedScreens.values
            .filter {
                sameDimension(it.dimensionKey, dimensionKey) &&
                        ServerOverrides.displayEnabled(DisplayPolicyCache.get(it.uuid)) &&
                        distanceToData(it, playerPos) <= DisplayPolicyCache.renderDistanceFor(DisplayPolicyCache.get(it.uuid))
            }
            .sortedBy { distanceToData(it, playerPos) }
            .forEach { data ->
                if (!fitsBudget(distanceToData(data, playerPos), playerPos)) return@forEach
                DisplayRegistry.unloadedScreens.remove(data.uuid)
                restoreScreen(data)
            }
    }

    /**
     * The displays past the viewer's "max playing displays" setting: everything but the nearest ones.
     *
     * A display already playing keeps its place against one a few blocks nearer, so two at about the
     * same distance do not keep swapping. Popped-out, virtual and broadcast-prepared displays are not counted.
     */
    fun overBudget(playerPos: BlockPos): Set<DisplayScreen> {
        val max = ServerOverrides.maxActiveDisplays
        if (max <= 0) return emptySet()
        val counted = DisplayRegistry.getScreens().filter { !it.isPopoutActive && !it.virtual && !it.isPrepared }
        if (counted.size <= max) return emptySet()
        return counted
            .sortedBy { it.getDistanceToScreen(playerPos) + if (it.isDormant) BUDGET_HYSTERESIS_BLOCKS else 0 }
            .drop(max)
            .toSet()
    }

    private fun fitsBudget(distance: Double, playerPos: BlockPos): Boolean {
        val max = ServerOverrides.maxActiveDisplays
        if (max <= 0) return true
        val playing = DisplayRegistry.getScreens()
            .filter { !it.isPopoutActive && !it.virtual && !it.isDormant && !it.isPrepared }
            .map { it.getDistanceToScreen(playerPos) }
        return playing.size < max || distance + BUDGET_HYSTERESIS_BLOCKS < playing.max()
    }

    private fun sameDimension(cached: String, current: String): Boolean =
        cached.isEmpty() || cached == current

    /**
     * The dimension [packet]'s display stands in: the server's world key (protocol 12), or else the player's current
     * dimension (older servers only describe displays in the player's own world, fullscreen and PiP aside).
     */
    private fun worldOf(packet: DisplayInfo): String = displayWorld(packet.world, currentDimensionKey())

    /** Holds its level weakly: the cache must not keep a disconnected world (and its chunks) alive. */
    private class DimensionOf(level: ClientLevel, val key: String) {
        val level = java.lang.ref.WeakReference(level)
    }

    @Volatile
    private var dimensionOf: DimensionOf? = null

    /** The player's current dimension id, or empty outside a world. Cached per level (read every frame per display). */
    internal fun currentDimensionKey(): String {
        val level = Minecraft.getInstance().level ?: return ""
        dimensionOf?.takeIf { it.level.get() === level }?.let { return it.key }
        return dimensionKeyOf(level).also { dimensionOf = DimensionOf(level, it) }
    }

    private fun dimensionKeyOf(level: ClientLevel): String =
        //? if >=1.21.11 {
        level.dimension().identifier().toString()
    //?} else
    /*level.dimension().location().toString()*/

    private fun restoreScreen(data: DisplayData) {
        if (!isValidDisplaySize(data.width, data.height, data.depth)) {
            logger.warn("Skipping cached display ${data.uuid}: invalid size ${data.width}x${data.height}x${data.depth}.")
            DisplayStorage.removeDisplay(data.uuid)
            return
        }

        val displayScreen = DisplayScreen(
            data.uuid, data.ownerUuid, data.x, data.y, data.z, data.facing,
            data.width, data.height, data.mode ?: PlaybackMode.LOCAL,
            qualityCap = data.qualityCap, rotation = DisplayRotation.fromQuarterTurns(data.rotation),
            dimensionKey = data.dimensionKey.ifEmpty { currentDimensionKey() },
            depth = data.depth.coerceAtLeast(1), conforming = data.conforming,
        )
        displayScreen.savedTimeNanos = data.currentTimeNanos
        displayScreen.volume = data.volume
        displayScreen.quality = VideoQuality.parse(data.quality)
        displayScreen.brightness = data.brightness
        displayScreen.muted = data.muted

        // The policy, lock, access and schedule the server last described, before the player starts
        lastInfo[data.uuid]?.let(displayScreen::applyInfoFields)

        displayScreen.createTexture()
        DisplayRegistry.screens[displayScreen.uuid] = displayScreen
        DisplayRegistry.recordScreen(displayScreen)

        if (data.videoUrl.isNotEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang)
            // Back in range: open the stream where the shared clock is, from the timeline the server kept sending,
            // not at the video's beginning while the one it asks for is on its way
            if (displayScreen.followsServerTimeline) {
                com.dreamdisplays.platform.client.displays.RecentTimelines.replayable(
                    data.uuid, data.videoUrl, com.dreamdisplays.platform.client.displays.ServerMediaGens.of(data.uuid),
                )?.let { displayScreen.updateData(it) }
            }
        }
    }

    private fun distanceToData(data: DisplayData, playerPos: BlockPos) =
        distanceToScreen(data.uuid, data.x, data.y, data.z, data.width, data.height, data.facing, playerPos, data.depth)

    private fun distanceToScreen(
        id: UUID, x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing, playerPos: BlockPos,
        depth: Int = 1,
    ): Double =
        PlacedDisplays.distance(id, width, height, depth, facing, playerPos)
            ?: DisplayGeometry.distanceTo(
                playerPos.x, playerPos.y, playerPos.z, x, y, z, width, height, facing, depth.coerceAtLeast(1),
            )

    private fun isValidDisplaySize(width: Int, height: Int, depth: Int = 1): Boolean =
        width in 1..MAX_DISPLAY_BLOCKS && height in 1..MAX_DISPLAY_BLOCKS && depth in 1..MAX_DISPLAY_BLOCKS
}

/** The display's dimension: [serverWorld] when the server named one, else [playerDimension] (older servers). */
internal fun displayWorld(serverWorld: String, playerDimension: String): String = serverWorld.ifEmpty { playerDimension }
