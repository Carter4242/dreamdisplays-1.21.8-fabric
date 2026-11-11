package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.protocol.common.ClientBuild
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

/**
 * `/display clients`: every online player with the mod, their mod version and client build id; players whose
 * build is not `[clients] latest_build` are marked (when that key is set). Jars from before the build id
 * report none and show as `older`. Same permission as `/display policy`; output only goes to the sender.
 */
@PaperOnly
object ClientsCommand {
    private const val OLDER = "older"

    fun execute(sender: CommandSender) {
        val latest = expectedBuild()
        val versions = PlayerManager.getVersions()
        val rows = Bukkit.getOnlinePlayers()
            .filter { V2PlayerTracker.isV2(it.uniqueId) || versions.containsKey(it.uniqueId) }
            .map { player ->
                val hello = V2PlayerTracker.helloOf(player.uniqueId)
                val version = hello?.modVersion?.takeIf { it.isNotBlank() }?.take(32)
                    ?: versions[player.uniqueId]?.toString() ?: "?"
                Triple(player.name, version, hello?.buildCommit)
            }
            .sortedBy { it.first.lowercase() }
        if (rows.isEmpty()) {
            ServerMessage.CLIENTS_NONE.sendTo(sender)
            return
        }
        val differing = rows.count { ClientBuild.differs(it.third, latest) }
        ServerMessage.CLIENTS_HEADER.sendTo(sender, rows.size.toString(), latest.ifEmpty { "unknown" }, differing.toString())
        rows.forEach { (name, version, commit) ->
            val build = ClientBuild.of(commit) ?: OLDER
            val message = if (ClientBuild.differs(commit, latest)) ServerMessage.CLIENTS_LINE_OUTDATED
            else ServerMessage.CLIENTS_LINE
            message.sendTo(sender, name, version, build)
        }
    }

    /** The build clients should run: `[clients] latest_build`, else this plugin's own build (empty when unknown). */
    fun expectedBuild(): String =
        ClientBuild.expected(PaperServer.config.settings.clients.latest_build, com.dreamdisplays.util.BuildInfo.commit)

    /**
     * [playerId]'s build label only when it is not the [expectedBuild] (`older` for jars without one), else null; also
     * null for players without the mod.
     */
    fun mismatchLabel(playerId: java.util.UUID): String? {
        val hello = V2PlayerTracker.helloOf(playerId) ?: return null
        if (!ClientBuild.differs(hello.buildCommit, expectedBuild())) return null
        return ClientBuild.of(hello.buildCommit) ?: OLDER
    }

    /** Short build label for [playerId] (`1a2b3c4d`, or `older` for jars without one), or null without the mod. */
    fun buildLabel(playerId: java.util.UUID): String? =
        V2PlayerTracker.helloOf(playerId)?.let { ClientBuild.of(it.buildCommit) ?: OLDER }
}
