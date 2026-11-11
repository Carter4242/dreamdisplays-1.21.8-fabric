package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.command.Perm
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.net.DisplayActions
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

/**
 * `/display mode <display> [synced|broadcast|local]`: shows or sets a display's base playback mode. The display menu
 * has no mode control, so this is how a display's mode changes. Applied like the old menu switch did
 * ([DisplayActions.applyMode]); a shared clock keeps its place when switching between Synced and Broadcast.
 */
@PaperOnly
object ModeCommand {
    /** Permission node (op by default, see `paper-plugin.yml`). */
    const val PERMISSION = Perm.PLAYBACK_MODE

    /** Entry point; [value] is null when not typed. */
    fun execute(sender: CommandSender, token: String, value: String?) {
        val display = resolve(sender, token) ?: return
        if (value == null) {
            ServerMessage.MODE_CURRENT.sendTo(sender, display.shortLabel, label(display.mode))
            return
        }
        val mode = PlaybackMode.baseModes.firstOrNull { label(it) == value.lowercase(Locale.ROOT) }
        if (mode == null) {
            ServerMessage.MODE_BAD_VALUE.sendTo(sender, value, valueSuggestions().joinToString(", "))
            return
        }
        if (mode == display.mode) {
            ServerMessage.MODE_CURRENT.sendTo(sender, display.shortLabel, label(mode))
            return
        }
        val paperDisplay = display as? PaperDisplayData ?: return
        DisplayActions.applyMode(paperDisplay, mode, TimelineManager.positionOf(display) ?: 0L, sender as? org.bukkit.entity.Player)
        ServerMessage.MODE_SET.sendTo(sender, display.shortLabel, label(mode))
    }

    /** Suggestions for the mode slot. */
    fun valueSuggestions(): List<String> =
        [PlaybackMode.SYNCED, PlaybackMode.BROADCAST, PlaybackMode.LOCAL].map(::label)

    /** Whether [sender] may use this command. */
    fun allowed(sender: CommandSender): Boolean = sender.hasPermission(PERMISSION)

    private fun label(mode: PlaybackMode): String = mode.name.lowercase(Locale.ROOT)

    private fun resolve(sender: CommandSender, token: String): DisplayData? = resolvePaperDisplay(sender, token)
}
