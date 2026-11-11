package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.command.TargetMatch
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** How far `this` reaches: the display the player looks at within this many blocks. */
private const val LOOK_REACH = 32

/** Every registered display, as [TargetMatch] sees it. */
private fun candidates(): List<TargetMatch.Candidate> =
    DisplayManager.getDisplays().map { TargetMatch.Candidate(it.id, it.name) }

/**
 * The display [token] names for any sender, the one way every `/display` command reads a display: `this` (or null) is
 * the display a player looks at; otherwise a full id, a name, or a unique id prefix (see [TargetMatch]). Tells
 * [sender] why when there is none and returns null.
 */
@PaperOnly
fun resolvePaperDisplay(sender: CommandSender, token: String?): DisplayData? =
    when (val match = TargetMatch.match(token, candidates())) {
        TargetMatch.Result.LookedAt -> {
            val player = sender as? Player
            if (player == null) {
                MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, "cmd.error.needsPlayer"))
                null
            } else {
                DisplayManager.isContains(player.getTargetBlock(null, LOOK_REACH).location) ?: run {
                    MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, "cmd.error.notLooking", LOOK_REACH.toString()))
                    null
                }
            }
        }

        is TargetMatch.Result.Found -> DisplayManager.getDisplayData(match.id)
        TargetMatch.Result.NotFound -> {
            MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, "cmd.error.noDisplay", token.orEmpty()))
            null
        }

        is TargetMatch.Result.Ambiguous -> {
            MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatIndexed(
                    sender, "cmd.error.ambiguous", token.orEmpty(), match.labels.size.toString(), match.labels.joinToString(", "),
                ),
            )
            null
        }
    }

/** [resolvePaperDisplay] for commands that already hold the sending [player]. */
@PaperOnly
fun resolvePaperDisplayTarget(sender: CommandSender, player: Player, token: String): DisplayData? =
    resolvePaperDisplay(sender, token)

/** The display [token] names by id, name or prefix (never `this`), without telling anyone when there is none. */
fun findDisplayQuietly(token: String): DisplayData? =
    (TargetMatch.match(token, candidates()) as? TargetMatch.Result.Found)?.let { DisplayManager.getDisplayData(it.id) }

/** `Fabric` / `NeoForge` counterpart of [resolvePaperDisplay], for a player sender. */
fun resolveVanillaDisplayTarget(player: ServerPlayer, token: String): DisplayData? =
    when (val match = TargetMatch.match(token, candidates())) {
        TargetMatch.Result.LookedAt -> {
            val targetPos = RegionUtil.getTargetedBlockPos(player)
            val display = targetPos?.let { DisplayManager.isContains(RegionUtil.getPlayerLevelKey(player), it) }
            display ?: run {
                MessageUtil.sendColoredMessage(player, MessageUtil.formatIndexed(player, "cmd.error.notLooking", LOOK_REACH.toString()))
                null
            }
        }

        is TargetMatch.Result.Found -> DisplayManager.getDisplayData(match.id)
        TargetMatch.Result.NotFound -> {
            MessageUtil.sendColoredMessage(player, MessageUtil.formatIndexed(player, "cmd.error.noDisplay", token))
            null
        }

        is TargetMatch.Result.Ambiguous -> {
            MessageUtil.sendColoredMessage(
                player,
                MessageUtil.formatIndexed(
                    player, "cmd.error.ambiguous", token, match.labels.size.toString(), match.labels.joinToString(", "),
                ),
            )
            null
        }
    }
