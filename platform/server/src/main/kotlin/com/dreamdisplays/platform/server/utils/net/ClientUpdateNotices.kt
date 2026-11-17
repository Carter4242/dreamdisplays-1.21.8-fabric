package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.ClientBuild
import com.dreamdisplays.core.protocol.common.ClientUpdateCheck
import com.dreamdisplays.core.protocol.common.CommitHistory
import com.dreamdisplays.core.protocol.common.ModVersion
import com.dreamdisplays.core.protocol.common.UpdateNoticeSessions
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.commands.subcommands.ClientsCommand
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Tells players on an outdated client build to update ([ClientUpdateCheck]): once per login, after their hello. Older
 * builds get the chat message after `[clients] update_notice_delay_seconds`; urgent ones after
 * `urgent_notice_delay_seconds`, with `urgent_sound` played to them only. Players already online when the plugin
 * started (a hot swap greets them and they say hello again) are skipped: their login was handled before.
 */
@PaperOnly
object ClientUpdateNotices {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val HISTORY_RESOURCE = "/dreamdisplays-commits.txt"
    private const val NONE = "none"

    private val sessions = UpdateNoticeSessions()

    /** The commit graph this plugin was built from; empty when it was built without git. */
    private val history: CommitHistory by lazy {
        val text = runCatching {
            ClientUpdateNotices::class.java.getResourceAsStream(HISTORY_RESOURCE)?.use { it.readBytes().decodeToString() }
        }.getOrNull().orEmpty()
        CommitHistory.parse(text).also {
            if (it.isEmpty) logger.warn("No commit history bundled: client builds are only compared by mod version.")
        }
    }

    @Volatile
    private var warnedUnknownLatest: String? = null

    /** The plugin started: everyone online now logged in under an earlier instance. */
    fun onEnable(online: Collection<UUID>) = sessions.pluginStarted(online)

    fun onQuit(player: UUID) = sessions.onQuit(player)

    /** [player]'s client said hello: schedules their update notice when their build needs one and none went out yet. */
    fun onHello(player: Player, hello: ClientHello) {
        val config = PaperServer.config.settings.clients
        if (!config.update_notice) return
        val verdict = runCatching { ClientUpdateCheck.classify(hello.modVersion, hello.buildCommit, rules()) }
            .onFailure { logger.warn("Could not classify {}'s client build.", player.name, it) }
            .getOrNull() ?: return
        if (verdict == ClientUpdateCheck.Verdict.CURRENT) return
        val token = sessions.begin(player.uniqueId) ?: return
        val urgent = verdict == ClientUpdateCheck.Verdict.URGENT
        val delaySeconds = if (urgent) config.urgent_notice_delay_seconds else config.update_notice_delay_seconds
        val send = Runnable {
            if (sessions.isCurrent(player.uniqueId, token)) send(player, hello, urgent)
        }
        if (delaySeconds <= 0) Scheduler.runPlayer(player, send)
        else Scheduler.runPlayerLater(player, delaySeconds * 20L, send)
    }

    private fun rules(): ClientUpdateCheck.Rules {
        val config = PaperServer.config.settings.clients
        val latestBuild = ClientsCommand.expectedBuild()
        if (latestBuild.isNotEmpty() && history.resolve(latestBuild) == null && warnedUnknownLatest != latestBuild) {
            warnedUnknownLatest = latestBuild
            logger.warn(
                "The current client build {} is not in this plugin's commit history: builds of the same version can't be " +
                        "called older. Set [clients] latest_build to a commit this plugin was built from or after.",
                latestBuild,
            )
        }
        return ClientUpdateCheck.Rules(
            latestVersion = ModVersion.parse(config.latest_version.ifEmpty { pluginVersion() }),
            latestBuild = latestBuild,
            history = history,
            urgentBuilds = config.urgent_builds,
            urgentBefore = config.urgent_before.takeUnless { it == NONE }.orEmpty(),
            urgentUnknown = config.urgent_unknown,
        )
    }

    @Suppress("DEPRECATION")
    private fun pluginVersion(): String = PaperServer.getInstance().description.version

    private fun send(player: Player, hello: ClientHello, urgent: Boolean) {
        val config = PaperServer.config.settings.clients
        MessageUtil.sendComponent(player, message(player, config.update_link))
        if (urgent) playSound(player, config.urgent_sound)
        logger.info(
            "Update notice sent to {} ({}, Dream Displays {}, build {}).",
            player.name,
            if (urgent) "urgent" else "older build",
            hello.modVersion.take(32).filter { !it.isISOControl() },
            ClientBuild.of(hello.buildCommit) ?: "none",
        )
    }

    /** The lang message with `{0}` replaced by the link text, which opens [url] and shows it on hover. */
    private fun message(player: Player, url: String): Component {
        val legacy = LegacyComponentSerializer.legacyAmpersand()
        val template = MessageUtil.messageFor(player, ServerMessage.CLIENT_UPDATE_NOTICE)
        val link = legacy.deserialize(MessageUtil.messageFor(player, ServerMessage.CLIENT_UPDATE_NOTICE_LINK))
            .clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText(Component.text(url)))
        val at = template.indexOf("{0}")
        if (at < 0) return legacy.deserialize("$template ").append(link)
        return Component.empty()
            .append(legacy.deserialize(template.substring(0, at)))
            .append(link)
            .append(legacy.deserialize(template.substring(at + 3)))
    }

    private fun playSound(player: Player, sound: String) {
        if (sound.isEmpty() || sound == NONE) return
        runCatching { player.playSound(player.location, sound, SoundCategory.MASTER, 1f, 1f) }
            .onFailure { logger.warn("Could not play the update notice sound {}: {}", sound, it.toString()) }
    }
}
