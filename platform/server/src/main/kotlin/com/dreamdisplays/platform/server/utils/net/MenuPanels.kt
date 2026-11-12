package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.activity.PanelVisibility
import com.dreamdisplays.core.activity.ViewerStatus
import com.dreamdisplays.core.activity.ViewerVia
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.MenuPanelRequest
import com.dreamdisplays.core.protocol.common.packets.ViewerEntryWire
import com.dreamdisplays.core.protocol.common.packets.ViewerState
import com.dreamdisplays.core.protocol.common.packets.ViewersState
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.playback.DisplayActivity
import com.dreamdisplays.platform.server.playback.PaperPlaybackTransport
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.policy.ClientDiagnostics
import com.dreamdisplays.shared.capability.ClientFeature
import com.dreamdisplays.shared.capability.ServerFeature
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The display menu's Activity and Viewers tabs on `Paper`: who may see them (`[activity] visible_to` /
 * `viewers_visible_to`, and only at the display: in its range, or following it from afar), the panel requests of
 * clients that show them, and the Viewers list, re-sent to players who have it open whenever it changes ([tick]).
 */
@PaperOnly
@NullMarked
object MenuPanels {
    private val requestThrottle = ActionThrottle()
    private const val REQUEST_COOLDOWN_MS = 150L

    /** Most rows a Viewers list has (the count still covers everyone). */
    private const val MAX_VIEWER_ROWS = 100

    /** Player -> the display whose Viewers list they follow, and the last list they got (to send only changes). */
    private val viewerWatchers = ConcurrentHashMap<UUID, UUID>()
    private val lastViewers = ConcurrentHashMap<UUID, ViewersState>()

    private val settings get() = PaperServer.config.settings.activity

    /** Points [DisplayActivity] at this platform's rules; call at enable. */
    fun init() {
        DisplayActivity.maxEntries = { settings.max_entries }
        com.dreamdisplays.platform.server.playback.ActivityJournal.dir =
            { java.io.File(com.dreamdisplays.platform.server.PaperServer.getInstance().dataFolder, "activity") }
        com.dreamdisplays.platform.server.playback.ActivityJournal.keepDays = { settings.log_days }
        DisplayActivity.placeOf = { display ->
            (display as? PaperDisplayData)?.takeIf { !it.virtual }?.pos1?.let { loc ->
                com.dreamdisplays.core.activity.ActivityJournalFormat.Place(loc.world?.name ?: "?", loc.blockX, loc.blockY, loc.blockZ)
            }
        }
        DisplayActivity.mayView = { playerId, displayId ->
            val player = Bukkit.getPlayer(playerId)
            val display = DisplayManager.getDisplayData(displayId) as? PaperDisplayData
            player != null && display != null && mayView(PanelVisibility.parse(settings.visible_to), player, display)
        }
    }

    /** The panel features [player]'s hello advertises: each only when it is on and allowed to them. */
    fun helloFeatures(player: Player): List<String> = buildList {
        val admin = isAdmin(player)
        if (settings.max_entries > 0 && PanelVisibility.parse(settings.visible_to).allows(admin)) add(ServerFeature.ACTIVITY_LOG.wire)
        if (PanelVisibility.parse(settings.viewers_visible_to).allows(admin)) add(ServerFeature.VIEWER_LIST.wire)
    }

    /** A client opened, switched or closed a display menu's panels. */
    fun onRequest(player: Player, packet: MenuPanelRequest) {
        val closing = !packet.activity && !packet.viewers
        // A close always goes through: it only ever stops traffic
        if (!closing && !requestThrottle.tryAcquire(player.uniqueId, REQUEST_COOLDOWN_MS)) return
        val hello = V2PlayerTracker.helloOf(player.uniqueId)
        val display = DisplayManager.getDisplayData(packet.id) as? PaperDisplayData
        DisplayActivity.subscribe(
            player.uniqueId, packet.id, display != null && packet.activity && hello?.hasFeature(ClientFeature.ACTIVITY_LOG) == true,
        )
        val viewers = display != null && packet.viewers && hello?.hasFeature(ClientFeature.VIEWER_LIST) == true &&
            mayView(PanelVisibility.parse(settings.viewers_visible_to), player, display)
        if (!viewers) {
            viewerWatchers.remove(player.uniqueId)
            lastViewers.remove(player.uniqueId)
            return
        }
        viewerWatchers[player.uniqueId] = packet.id
        lastViewers.remove(player.uniqueId)
        sendViewers(player, display)
    }

    /** Re-sends each followed Viewers list that changed; once a second. */
    fun tick() {
        if (viewerWatchers.isEmpty()) return
        val visibility = PanelVisibility.parse(settings.viewers_visible_to)
        for ((playerId, displayId) in viewerWatchers) {
            val player = Bukkit.getPlayer(playerId)
            val display = DisplayManager.getDisplayData(displayId) as? PaperDisplayData
            if (player == null || display == null || !mayView(visibility, player, display)) {
                viewerWatchers.remove(playerId, displayId)
                lastViewers.remove(playerId)
                continue
            }
            sendViewers(player, display)
        }
    }

    fun onQuit(playerId: UUID) {
        viewerWatchers.remove(playerId)
        lastViewers.remove(playerId)
        DisplayActivity.onQuit(playerId)
    }

    /** A display was deleted: nobody follows it any more. */
    fun onDisplayRemoved(displayId: UUID) {
        viewerWatchers.entries.removeIf { it.value == displayId }
        DisplayActivity.forget(displayId)
    }

    private fun sendViewers(player: Player, display: PaperDisplayData) {
        val state = viewersOf(player, display)
        if (lastViewers[player.uniqueId] == state) return
        lastViewers[player.uniqueId] = state
        PaperV2Networking.send([player], state)
    }

    /**
     * Everyone who gets [display] now, as [requester] may know it: the display's audience (in range, far fullscreen
     * followers, PiP pinners: the same sets its timeline goes to), minus players [requester] cannot see and players
     * who turned displays off. Players in range without the mod are listed last and not counted.
     */
    internal fun viewersOf(requester: Player, display: PaperDisplayData): ViewersState {
        val nearby = PaperPlaybackTransport.nearbyPlayerIds(display)
        val far = TimelineManager.extraReceivers(display.id)
        val pins = TimelineManager.pinReceivers(display.id)
        val reports = ClientDiagnostics.viewers(display.id).associateBy { it.playerId }
        val rows = ArrayList<ViewerEntryWire>()
        val seen = HashSet<UUID>()
        fun add(id: UUID, via: ViewerVia) {
            if (!seen.add(id)) return
            val player = Bukkit.getPlayer(id) ?: return
            if (player != requester && !requester.canSee(player)) return
            if (!PlayerManager.isDisplaysEnabled(id)) return
            val status = when {
                !V2PlayerTracker.isV2(id) -> ViewerStatus.NO_MOD
                else -> when (reports[id]?.state) {
                    ViewerState.LOADING -> ViewerStatus.LOADING
                    ViewerState.BUFFERING -> ViewerStatus.BUFFERING
                    ViewerState.ERROR -> ViewerStatus.ERROR
                    else -> ViewerStatus.WATCHING
                }
            }
            rows += ViewerEntryWire(id, player.name, via.wire, status.wire)
        }
        nearby.forEach { add(it, ViewerVia.NEARBY) }
        far.forEach { add(it, ViewerVia.FULLSCREEN) }
        pins.forEach { add(it, ViewerVia.PIP) }
        val withMod = rows.filter { it.status != ViewerStatus.NO_MOD.wire }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val without = rows.filter { it.status == ViewerStatus.NO_MOD.wire }.sortedBy { it.name.lowercase(Locale.ROOT) }
        return ViewersState(display.id, (withMod + without).take(MAX_VIEWER_ROWS), total = withMod.size)
    }

    /** True when [player] could open [display]'s menu: in its range, or following it from afar (fullscreen, PiP). */
    fun isPresent(player: Player, display: PaperDisplayData): Boolean =
        DisplayManager.isPlayerInRange(player, display) ||
            player.uniqueId in TimelineManager.extraReceivers(display.id) ||
            player.uniqueId in TimelineManager.pinReceivers(display.id)

    private fun mayView(visibility: PanelVisibility, player: Player, display: PaperDisplayData): Boolean =
        PanelVisibility.mayView(visibility, isAdmin(player), isPresent(player, display))

    private fun isAdmin(player: Player) = player.hasPermission(PaperServer.config.permissions.deleteOthers)
}
