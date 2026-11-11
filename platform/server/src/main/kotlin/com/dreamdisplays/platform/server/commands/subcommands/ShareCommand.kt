package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.command.Perm
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.net.ScreenShareActions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/display share stop [display]`: ends the screen share running on a display at once. The sharer's capture is told
 * to stop and the display gets its previous video back. Op-only ([PERMISSION]).
 */
@PaperOnly
object ShareCommand {
    /** Permission node (op by default, see `paper-plugin.yml`). */
    const val PERMISSION = Perm.SHARE_MANAGE

    /** Whether [sender] may use this command. */
    fun allowed(sender: CommandSender): Boolean = sender.hasPermission(PERMISSION)

    /** Stops the share on the display [token] names (`this`, an id or id prefix, or a name). */
    fun stop(sender: CommandSender, token: String) {
        if (!allowed(sender)) return
        val display = resolve(sender, token) ?: return
        val sharer = ScreenShareActions.adminStop(display.id)
        if (sharer == null) return say(sender, "cmd.share.none", display.shortLabel)
        say(sender, "cmd.share.stopped", sharer, display.shortLabel)
    }

    /** `/display share list`: every display with a screen share running. */
    fun list(sender: CommandSender) {
        val displays = ScreenShareActions.sharedDisplays().mapNotNull { DisplayManager.getDisplayData(it) }
        if (displays.isEmpty()) return say(sender, "cmd.share.listNone")
        say(sender, "cmd.share.listHeader", displays.size.toString())
        displays.map { it.shortLabel }.sorted().forEach { say(sender, "cmd.share.listLine", it) }
    }

    private fun say(sender: CommandSender, key: String, vararg args: String) =
        MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, key, *args))

    /** Display ids (short form) with a share running, for suggestions. */
    fun sharedDisplaySuggestions(): List<String> = ScreenShareActions.sharedDisplays().map { it.toString().take(8) }.sorted()

    private fun resolve(sender: CommandSender, token: String): DisplayData? = resolvePaperDisplay(sender, token)
}
