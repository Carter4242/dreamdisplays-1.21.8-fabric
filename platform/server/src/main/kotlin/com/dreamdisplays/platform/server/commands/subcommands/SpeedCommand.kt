package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.command.Perm
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/display speed <display> [<rate>|reset]`: shows or sets the playback speed of a Synced / Broadcast display's
 * shared timeline (0.1 to 10, pitch kept). Everyone watching switches together.
 */
@PaperOnly
object SpeedCommand {
    /** Permission node (op by default, see `paper-plugin.yml`). */
    const val PERMISSION = Perm.PLAYBACK_SPEED

    private const val RESET = "reset"

    /** Entry point; [value] is null when not typed. */
    fun execute(sender: CommandSender, token: String, value: String?) {
        val display = resolve(sender, token) ?: return
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) {
            ServerMessage.SPEED_NOT_SHARED.sendTo(sender, display.shortLabel)
            return
        }
        if (value == null) {
            ServerMessage.SPEED_CURRENT.sendTo(sender, display.shortLabel, label(TimelineManager.speedOf(display)))
            return
        }
        val rate = if (value.equals(RESET, ignoreCase = true)) PlaybackSpeed.NORMAL else PlaybackSpeed.parse(value)
        if (rate == null) {
            ServerMessage.SPEED_BAD_VALUE.sendTo(sender, value, label(PlaybackSpeed.MIN), label(PlaybackSpeed.MAX))
            return
        }
        if (!TimelineManager.setSpeed(display, rate)) {
            ServerMessage.SPEED_NOT_SHARED.sendTo(sender, display.shortLabel)
            return
        }
        ServerMessage.SPEED_SET.sendTo(sender, display.shortLabel, label(TimelineManager.speedOf(display)))
    }

    /** Suggestions for the rate slot. */
    fun valueSuggestions(): List<String> = ["0.5", "0.75", "1.0", "1.25", "1.5", "2.0", RESET]

    /** Whether [sender] may use this command. */
    fun allowed(sender: CommandSender): Boolean = sender.hasPermission(PERMISSION)

    private fun label(rate: Double): String = PlaybackSpeed.format(rate) + "x"

    private fun resolve(sender: CommandSender, token: String): DisplayData? = resolvePaperDisplay(sender, token)
}
