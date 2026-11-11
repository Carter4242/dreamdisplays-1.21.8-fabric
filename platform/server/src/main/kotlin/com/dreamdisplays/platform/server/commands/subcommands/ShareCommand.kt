package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.net.ScreenShareActions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/display share stop <display>`: ends the screen share running on a display at once. The sharer's capture is told
 * to stop and the display gets its previous video back. Op-only ([PERMISSION]).
 */
@PaperOnly
object ShareCommand {
    /** Permission node (op by default, see `paper-plugin.yml`). */
    const val PERMISSION = "dreamdisplays.share.admin"

    /** Whether [sender] may use this command. */
    fun allowed(sender: CommandSender): Boolean = sender.hasPermission(PERMISSION)

    /** Stops the share on the display [token] names (`this`, an id or id prefix, or a name). */
    fun stop(sender: CommandSender, token: String) {
        if (!allowed(sender)) return
        val display = resolve(sender, token) ?: return
        val sharer = ScreenShareActions.adminStop(display.id)
        if (sharer == null) {
            MessageUtil.sendColoredMessage(sender, "&eNo screen share is running on display ${display.shortLabel}.")
            return
        }
        MessageUtil.sendColoredMessage(sender, "&aStopped $sharer's screen share on display ${display.shortLabel}.")
    }

    /** Display ids (short form) with a share running, for suggestions. */
    fun sharedDisplaySuggestions(): List<String> = ScreenShareActions.sharedDisplays().map { it.toString().take(8) }.sorted()

    private fun resolve(sender: CommandSender, token: String): DisplayData? {
        if (sender is Player && token.equals("this", ignoreCase = true)) {
            val block = sender.getTargetBlock(null, 32)
            return DisplayManager.isContains(block.location) ?: run {
                MessageUtil.sendColoredMessage(sender, "&cLook at a display, or name one by id.")
                null
            }
        }
        return DisplayManager.resolveByIdOrPrefix(token) ?: run {
            MessageUtil.sendColoredMessage(sender, "&cNo display matches '$token'.")
            null
        }
    }
}
