package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.display.policy.PolicyPaths
import com.dreamdisplays.core.display.region.RegionVisibility.Reason
import com.dreamdisplays.core.display.region.RegionVisibility.State
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayRegions
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender

/** What `/display info` and `/display policy` say about a display tied to a `WorldGuard` region (`visibility.region`). */
@PaperOnly
object RegionStatus {
    /** One line for [display]'s region, or nothing when it is tied to none. */
    fun send(sender: CommandSender, display: PaperDisplayData) {
        when (val state = DisplayRegions.stateOf(display)) {
            State.None -> Unit
            is State.Active -> ServerMessage.REGION_STATUS_ACTIVE.sendTo(
                sender, state.regionId, DisplayRegions.describeShape(state.shape),
                PolicyPaths.format(state.fadeBlocks), PolicyPaths.format(state.loadBlocks),
            )

            is State.Unavailable -> ServerMessage.REGION_STATUS_UNAVAILABLE.sendTo(
                sender, state.regionId, reasonText(sender, state.reason, display),
            )
        }
    }

    /** Why a region cannot be used, in [sender]'s language. */
    fun reasonText(sender: CommandSender, reason: Reason, display: PaperDisplayData): String = when (reason) {
        Reason.NO_WORLDGUARD -> MessageUtil.messageFor(sender, "regionReasonNoWorldGuard")
        Reason.NO_WORLD -> MessageUtil.formatPrintf(sender, "regionReasonNoWorld", display.pos1.world?.name ?: "?")
        Reason.NOT_FOUND -> MessageUtil.formatPrintf(sender, "regionReasonNotFound", display.pos1.world?.name ?: "?")
        Reason.UNSUPPORTED_SHAPE -> MessageUtil.messageFor(sender, "regionReasonUnsupported")
        Reason.VIRTUAL -> MessageUtil.messageFor(sender, "regionReasonVirtual")
    }
}
