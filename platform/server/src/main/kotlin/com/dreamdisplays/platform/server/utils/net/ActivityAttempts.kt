package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.protocol.common.packets.PlaybackCommand
import com.dreamdisplays.core.protocol.common.packets.QueueAction
import com.dreamdisplays.core.protocol.common.packets.QueueActionType
import com.dreamdisplays.core.protocol.common.packets.SetLocked
import com.dreamdisplays.core.protocol.common.packets.SetMode
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.DisplayActivity
import com.dreamdisplays.shared.playback.PlaybackPermissions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import java.util.*

/**
 * Refused display actions for the permanent Activity journal. Each action packet a player sends is an attempt
 * ([begin] before it is handled, [end] after): one that recorded no Activity row was turned down, and is journaled
 * with the likeliest reason, worked out here so the action code itself stays as it is. Screen share starts are not
 * attempts: they finish later, after the relay answers.
 */
@PaperOnly
object ActivityAttempts {
    class Attempt internal constructor(
        val player: Player, val displayId: UUID, val kind: ActivityKind, val url: String, val toMs: Long,
        internal val recordedBefore: Long,
    )

    /** The attempt [packet] from [player] is, or null when it is no display action. */
    fun begin(player: Player, packet: DreamPacket): Attempt? {
        DisplayActivity.handlingPacketFrom(null)
        val (id, kind, url, toMs) = when (packet) {
            is SetVideo -> Quad(packet.id, if (packet.clear) ActivityKind.VIDEO_CLEAR else ActivityKind.VIDEO_SET, packet.url, 0L)
            is SetLocked -> Quad(packet.id, ActivityKind.ACCESS, "", 0L)
            is SetMode -> Quad(packet.id, ActivityKind.MODE, "", 0L)
            is PlaybackCommand -> {
                val action = PlaybackAction.fromWire(packet.action) ?: return null
                Quad(packet.id, ActivityKind.ofPlayback(action), "", if (action == PlaybackAction.SEEK) packet.positionMs else 0L)
            }
            is QueueAction -> Quad(packet.id, queueKind(packet.action) ?: return null, packet.url, 0L)
            is DisplayDelete -> Quad(packet.id, ActivityKind.DELETED, "", 0L)
            else -> return null
        }
        DisplayActivity.handlingPacketFrom(player.uniqueId)
        return Attempt(player, id, kind, url, toMs, DisplayActivity.recordedOnThisThread())
    }

    /** [attempt] was handled: journal it as refused when it recorded nothing. */
    fun end(attempt: Attempt?) {
        attempt ?: return
        DisplayActivity.handlingPacketFrom(null)
        if (DisplayActivity.recordedOnThisThread() != attempt.recordedBefore) return
        DisplayActivity.refused(
            attempt.displayId, attempt.kind, attempt.player.uniqueId, attempt.player.name, reasonFor(attempt),
            url = attempt.url, toMs = attempt.toMs,
        )
    }

    private data class Quad(val id: UUID, val kind: ActivityKind, val url: String, val toMs: Long)

    private fun queueKind(wire: Int): ActivityKind? = when (QueueActionType.fromWire(wire)) {
        QueueActionType.ADD -> ActivityKind.QUEUE_ADD
        QueueActionType.ADD_BATCH -> ActivityKind.QUEUE_ADD_PLAYLIST
        QueueActionType.REMOVE -> ActivityKind.QUEUE_REMOVE
        QueueActionType.MOVE -> ActivityKind.QUEUE_MOVE
        QueueActionType.SKIP -> ActivityKind.QUEUE_SKIP
        QueueActionType.CLEAR -> ActivityKind.QUEUE_CLEAR
        QueueActionType.LOOP -> ActivityKind.QUEUE_LOOP
        null -> null
    }

    /** The likeliest reason [a] changed nothing: missing display, not there, mode, rules; else throttled / no change. */
    private fun reasonFor(a: Attempt): String {
        val display = DisplayManager.getDisplayData(a.displayId) as? PaperDisplayData ?: return "no such display"
        if (!MenuPanels.isPresent(a.player, display)) return "not at the display"
        val ctx = QueueActions.context(display, a.player)
        val allowed = when (a.kind) {
            ActivityKind.PLAY, ActivityKind.PAUSE, ActivityKind.SEEK, ActivityKind.RESTART, ActivityKind.SPEED -> {
                if (display.mode != PlaybackMode.SYNCED) return "display is not synced"
                PlaybackPermissions.canPlayPause(ctx)
            }
            ActivityKind.VIDEO_SET, ActivityKind.VIDEO_CLEAR -> PlaybackPermissions.canSetVideo(ctx)
            ActivityKind.MODE -> PlaybackPermissions.canSetMode(ctx)
            ActivityKind.ACCESS -> PlaybackPermissions.canToggleLock(ctx)
            ActivityKind.QUEUE_ADD, ActivityKind.QUEUE_ADD_PLAYLIST -> PlaybackPermissions.canAddToQueue(ctx)
            ActivityKind.QUEUE_SKIP -> PlaybackPermissions.canSkipQueue(ctx)
            ActivityKind.QUEUE_MOVE, ActivityKind.QUEUE_CLEAR, ActivityKind.QUEUE_LOOP -> PlaybackPermissions.canManageQueue(ctx)
            ActivityKind.DELETED -> ctx.isOwner || ctx.isAdmin
            else -> true
        }
        if (!allowed) return "not allowed"
        return "throttled, invalid or no change"
    }
}
