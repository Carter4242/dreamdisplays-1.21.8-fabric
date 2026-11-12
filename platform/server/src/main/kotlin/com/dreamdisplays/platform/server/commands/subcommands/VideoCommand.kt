package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.client.utils.literalText
import com.dreamdisplays.platform.server.playback.DisplayActivity
import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.shared.policy.LanguageTag
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.dreamdisplays.platform.server.utils.net.CustomMediaGate
import com.dreamdisplays.platform.server.utils.net.DisplayActions
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*

/**
 * Handles the `/display video` command. Assigns a YouTube URL (and optional audio language)
 * to the display the player is looking at, after validating ownership.
 */
@PaperOnly
class VideoCommand : SubCommand {
    override val name = "video"
    override val permission = PaperServer.config.permissions.video
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return
        if (args.size < 2) {
            ServerMessage.INVALID_URL.sendTo(player)
            return
        }
        val token = args[0] ?: "this"

        // Any URL the mod can resolve is accepted, not just YouTube: the same custom links the
        // menu takes work here too, subject to the same server policy applied further down.
        val requestedUrl = canonicalUrl(args[1] ?: "")
        if (requestedUrl == null) {
            ServerMessage.INVALID_URL.sendTo(player)
            return
        }

        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return

        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.deleteOthers))
            )
        ) {
            ServerMessage.DISPLAY_VIDEO_NOT_OWNER.sendTo(player)
            return
        }

        CustomMediaGate.refusalKey(
            requestedUrl,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let {
            MessageUtil.sendMessage(player, it)
            return
        }

        val canonicalLang = LanguageTag.canonicalAudioCode(args.getOrNull(2)).value
        ServerApiHost.current?.askSetUrl(data, requestedUrl, canonicalLang, player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        DisplayActivity.record(data, ActivityKind.VIDEO_SET, player.uniqueId, url = requestedUrl, title = DisplayActivity.titleFor(data, requestedUrl), old = data.url)
        data.apply {
            url = requestedUrl
            lang = canonicalLang
        }

        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        DisplayManager.broadcastUpdate(data)
        TimelineManager.onVideoChanged(data)
        QueueManager.onVideoSetByHand(data)
        ServerApiHost.current?.urlChanged(data)

        ServerMessage.SETTED_URL.sendTo(player)
    }

    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        if (args.size == 3) {
            return languageSuggestions
        }
        return []
    }

    companion object {
        val languageSuggestions: List<String> by lazy {
            val fromJavaLocales = Locale.getAvailableLocales()
                .asSequence()
                .map { it.language.lowercase(Locale.ROOT) }

            val fromPlugin = PaperServer.config.languages.keys
                .asSequence()
                .map { it.trim().lowercase(Locale.ROOT).replace('-', '_').substringBefore('_') }

            return@lazy (fromJavaLocales + fromPlugin)
                .filter { it.matches(Regex("^[a-z]{2}$")) }
                .map { code ->
                    if (code == "uk") "ua" else code
                }
                .distinct()
                .sorted()
                .toList()
        }
    }
}

private fun canonicalUrl(raw: String): String? {
    val input = raw.trim()
    if (input.isEmpty()) return null
    val resolvable = MediaSource.from(input).toResolvableUrl() ?: return null
    if (!resolvable.contains("://")) return null
    return resolvable.takeIf { MediaUrlPolicy.isAllowed(it) }
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display video` command.
 */
@ModLoaderOnly
object VanillaVideoCommand {
    /** Assigns a YouTube URL (and optional language) to the targeted display, after validating ownership. */
    fun execute(ctx: CommandContext<CommandSourceStack>, token: String, urlAndLang: String): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(literalText("Players only.")).let { 0 }

        val parts = urlAndLang.trim().split(" ")
        val urlRaw = parts[0]
        val langRaw = if (parts.size > 1) parts.last() else ""

        if (urlRaw.isBlank()) {
            ServerMessage.INVALID_URL.sendTo(player)
            return 0
        }

        if (urlRaw.equals(com.dreamdisplays.core.command.DisplayCommands.CLEAR_VIDEO, ignoreCase = true)) {
            return clear(ctx, player, token)
        }

        val requestedUrl = canonicalUrl(urlRaw)
            ?: return ServerMessage.INVALID_URL.sendTo(player).let { 0 }

        val data = resolveVanillaDisplayTarget(player, token) as? VanillaDisplayData ?: return 0

        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uuid, VanillaDisplayActions.isAdmin(player))
            )
        ) {
            ServerMessage.DISPLAY_VIDEO_NOT_OWNER.sendTo(player)
            return 0
        }

        CustomMediaGate.refusalKey(
            requestedUrl,
            VanillaServerState.config.settings.customMediaPolicy,
            VanillaPermissions.has(
                player,
                VanillaServerState.config.permissions.custom,
                VanillaPermissions.Fallback.EVERYONE,
            ),
            player.uuid,
        )?.let { refusal ->
            MessageUtil.sendMessage(player, refusal)
            return 0
        }

        val canonicalLang = LanguageTag.canonicalAudioCode(langRaw).value
        ServerApiHost.current?.askSetUrl(data, requestedUrl, canonicalLang, player.uuid)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return 0
        }

        data.url = requestedUrl
        data.lang = canonicalLang
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }

        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        VanillaPacketUtil.sendDisplayInfo(receivers, data)
        TimelineManager.onVideoChanged(data)
        ServerApiHost.current?.urlChanged(data)

        ServerMessage.SETTED_URL.sendTo(player)
        return 1
    }

    /**
     * `/display video <display> clear`: takes the video off the display for everyone, with the same ownership rule as
     * setting one. Its clock, length and fullscreen sessions go; queued entries stay and wait for a skip or a play.
     */
    private fun clear(ctx: CommandContext<CommandSourceStack>, player: ServerPlayer, token: String): Int {
        val data = resolveVanillaDisplayTarget(player, token) as? VanillaDisplayData ?: return 0
        if (!PlaybackPermissions.canSetVideo(PlaybackContexts.of(data, player.uuid, VanillaDisplayActions.isAdmin(player)))) {
            ServerMessage.DISPLAY_VIDEO_NOT_OWNER.sendTo(player)
            return 0
        }
        ServerApiHost.current?.askSetUrl(data, "", "", player.uuid)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return 0
        }
        data.url = ""
        data.lang = ""
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }
        TimelineManager.onVideoCleared(data)
        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        // Clients older than protocol 12 ignore an empty URL and would play the cleared video on: they drop the display
        VanillaPacketUtil.sendDelete(
            receivers.filter {
                com.dreamdisplays.core.sync.EmptyDisplays.dropBeforeClear(V2PlayerTracker.helloOf(it.uuid)?.protocolVersion ?: 0)
            },
            data.id,
        )
        VanillaPacketUtil.sendDisplayInfo(receivers, data)
        com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager.endSessionsOf(data.id)
        QueueManager.onVideoSetByHand(data)
        ServerApiHost.current?.urlChanged(data)
        MessageUtil.sendColoredMessage(
            player, MessageUtil.formatIndexed(player, "cmd.video.cleared", data.name ?: data.id.toString().take(8)),
        )
        return 1
    }
}

/**
 * `/display video <display> clear`: takes the video off [display] for everyone, with the same ownership rule as
 * setting one. The clearing itself is [DisplayActions.clearVideo], the menu's Clear button's path (share ended; the
 * next queued entry starts as on a skip, or with none the display goes blank: karaoke stopped, clock dropped,
 * followers told).
 */
@PaperOnly
fun clearDisplayVideo(player: Player, display: PaperDisplayData) {
    val admin = player.hasPermission(PaperServer.config.permissions.deleteOthers)
    if (!PlaybackPermissions.canSetVideo(PlaybackContexts.of(display, player.uniqueId, admin))) {
        ServerMessage.DISPLAY_VIDEO_NOT_OWNER.sendTo(player)
        return
    }
    val key = if (DisplayActions.clearVideo(display, player)) "cmd.video.clearedNext" else "cmd.video.cleared"
    MessageUtil.sendColoredMessage(player, MessageUtil.formatIndexed(player, key, display.name ?: display.id.toString().take(8)))
}
