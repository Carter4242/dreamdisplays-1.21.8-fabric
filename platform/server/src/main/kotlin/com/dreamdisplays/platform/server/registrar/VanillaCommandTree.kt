package com.dreamdisplays.platform.server.registrar

//? if <1.19 {
import com.dreamdisplays.platform.server.utils.sendSystemMessage
//?}
import com.dreamdisplays.core.command.Call
import com.dreamdisplays.core.command.ChatLine
import com.dreamdisplays.core.command.CommandPlatform
import com.dreamdisplays.core.command.DisplayCommandTree
import com.dreamdisplays.core.command.Handler
import com.dreamdisplays.core.command.Kind
import com.dreamdisplays.core.command.Perm
import com.dreamdisplays.core.command.SuggestRequest
import com.dreamdisplays.core.command.Suggestion
import com.dreamdisplays.core.command.TargetMatch
import com.dreamdisplays.core.command.startingWith
import com.dreamdisplays.platform.client.utils.literalText
import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.commands.suggest.DisplaySuggestions
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import java.util.*

/**
 * The `Fabric` / `NeoForge` `/display` command: the same [com.dreamdisplays.core.command.DisplayCommands] spec as
 * `CommandRegistrar.kt` (Paper), with the subset of handlers these platforms have. Commands without one here are left
 * out of the tree and of help.
 */
@ModLoaderOnly
object VanillaCommandTree {
    /** Builds the full `/display` command tree, ready to attach to a dispatcher root. */
    fun build(): LiteralCommandNode<CommandSourceStack> = DisplayCommandTree(platform, handlers).root()

    private fun player(source: CommandSourceStack): ServerPlayer? = source.entity as? ServerPlayer

    private val platform = object : CommandPlatform<CommandSourceStack> {
        override fun has(source: CommandSourceStack, permission: String): Boolean {
            val player = player(source) ?: return true
            return VanillaPermissions.has(player, permission, VanillaPermissions.Fallback.OP)
        }

        override fun isPlayer(source: CommandSourceStack) = player(source) != null

        override fun text(source: CommandSourceStack, key: String, vararg args: String) =
            MessageUtil.formatIndexed(player(source), key, *args)

        override fun send(source: CommandSourceStack, lines: List<ChatLine>) {
            val player = player(source)
            lines.forEach { line ->
                if (player != null) MessageUtil.sendColoredMessage(player, line.text)
                else source.sendSystemMessage(literalText(line.text.replace(Regex("&[0-9a-fk-or]"), "")))
            }
        }

        override fun suggest(source: CommandSourceStack, request: SuggestRequest<CommandSourceStack>): List<Suggestion> =
            suggestions(source, request)
    }

    private fun h(block: (CommandContext<CommandSourceStack>, Call<CommandSourceStack>) -> Unit) =
        Handler<CommandSourceStack> { call -> block(call.context!!, call) }

    private val handlers: Map<String, Handler<CommandSourceStack>> = mapOf(
        "on" to h { ctx, c -> VanillaOnCommand.execute(ctx, c["player"]) },
        "off" to h { ctx, c -> VanillaOffCommand.execute(ctx, c["player"]) },
        "create" to h { ctx, _ -> VanillaCreateCommand.execute(ctx) },
        "delete" to h { ctx, c -> VanillaDeleteCommand.execute(ctx, c.display) },
        "info" to h { ctx, c -> VanillaInfoCommand.execute(ctx, c.display) },
        "name" to h { ctx, c -> VanillaNameCommand.execute(ctx, c.display, c["name"]) },
        "list" to h { ctx, c -> VanillaListCommand.execute(ctx, pageStr = c["page"]) },
        "list.mine" to h { ctx, c -> VanillaListCommand.execute(ctx, filter = "mine", pageStr = c["page"]) },
        "list.world" to h { ctx, c -> VanillaListCommand.execute(ctx, "world", c["world"], c["page"]) },
        "list.owner" to h { ctx, c -> VanillaListCommand.execute(ctx, "owner", c["owner"], c["page"]) },
        "list.mode" to h { ctx, c -> VanillaListCommand.execute(ctx, "mode", c["mode"], c["page"]) },
        "video" to h { ctx, c -> VanillaVideoCommand.execute(ctx, c.display, listOfNotNull(c["url"], c["lang"]).joinToString(" ")) },
        "schedule.show" to h { ctx, c -> VanillaScheduleCommand.execute(ctx, c.display, null, null) },
        "schedule.play" to h { ctx, c -> VanillaScheduleCommand.execute(ctx, c.display, "play", c["time"]) },
        "schedule.pause" to h { ctx, c -> VanillaScheduleCommand.execute(ctx, c.display, "pause", c["time"]) },
        "schedule.cancel" to h { ctx, c -> VanillaScheduleCommand.execute(ctx, c.display, "cancel", null) },
        "prepare.start" to h { ctx, c ->
            val id = sourceToken(ctx, c["source"]!!, network = false) ?: return@h
            VanillaFullscreenCommand.prepare(ctx, id, c.flags!!.prepareOptions())
        },
        "prepare.list" to h { ctx, _ -> VanillaFullscreenCommand.list(ctx) },
        "prepare.cancel" to h { ctx, c -> VanillaFullscreenCommand.stop(ctx, sessionToken(c["id"]!!)) },
        "fullscreen.start" to h { ctx, c ->
            val flags = c.flags!!
            val id = sourceToken(ctx, c["source"]!!, network = flags.has("server")) ?: return@h
            VanillaFullscreenCommand.start(ctx, id, flags.startOptions())
        },
        "fullscreen.list" to h { ctx, _ -> VanillaFullscreenCommand.list(ctx) },
        "fullscreen.stop" to h { ctx, c -> VanillaFullscreenCommand.stop(ctx, sessionToken(c["id"]!!)) },
        "reload" to h { ctx, _ -> VanillaReloadCommand.execute(ctx) },
        "diag.clients" to h { ctx, _ -> VanillaStatsCommand.execute(ctx) },
    )

    /** A URL as typed, else the display's full id (`this` needs a player); network broadcasts keep the token. */
    private fun sourceToken(ctx: CommandContext<CommandSourceStack>, token: String, network: Boolean): String? {
        if (network || token.contains("://")) return token
        val player = player(ctx.source)
        if (player == null) {
            if (TargetMatch.isThis(token)) {
                ctx.source.sendFailure(literalText(MessageUtil.formatIndexed(null as ServerPlayer?, "cmd.error.needsPlayer")))
                return null
            }
            return findDisplayQuietly(token)?.id?.toString() ?: token
        }
        return resolveVanillaDisplayTarget(player, token)?.id?.toString()
    }

    private fun sessionToken(token: String): String {
        if (token.equals(TargetMatch.ALL, ignoreCase = true)) return token
        if (FullscreenCommand.stopSuggestions().any { it.equals(token, ignoreCase = true) }) return token
        return findDisplayQuietly(token)?.id?.toString() ?: token
    }

    private fun suggestions(source: CommandSourceStack, r: SuggestRequest<CommandSourceStack>): List<Suggestion> {
        val typed = r.typed
        DisplaySuggestions.common(r.def, r.name, r.kind, typed, r.call.values)?.let { return it }
        val online = source.server.playerList.players.map { it.gameProfile.name }
        return when (r.kind) {
            Kind.PLAYER -> if ((r.def.id == "on" || r.def.id == "off") && !platform.has(source, Perm.VIEWING_TOGGLE_OTHERS)) {
                emptyList()
            } else DisplaySuggestions.players(online, typed)

            Kind.KNOWN_PLAYER -> DisplaySuggestions.players(online, typed)
            Kind.LANG -> LANGUAGES.map { Suggestion(it, "suggestLanguage") }.startingWith(typed)
            Kind.TIME -> DisplaySuggestions.scheduleTimes(player(source)?.let { ScheduleTimeUtil.offsetMinutesOf(it.uuid) } ?: 0, typed)
            else -> when (r.name) {
                "players" -> DisplaySuggestions.playerList(online, typed)
                "x" -> DisplaySuggestions.coordinate(source.position.x, "x", typed)
                "y" -> DisplaySuggestions.coordinate(source.position.y, "y", typed)
                "z" -> DisplaySuggestions.coordinate(source.position.z, "z", typed)
                "world" -> DisplayManager.getDisplays().filterIsInstance<VanillaDisplayData>()
                    .map { it.worldKey.substringAfterLast(':') }.distinct().sorted()
                    .map { Suggestion(it, "suggestWorld") }.startingWith(typed)

                "owner" -> {
                    val owners = DisplayManager.getDisplays().map { it.ownerId }.toSet()
                    source.server.playerList.players.filter { it.uuid in owners }.map { it.gameProfile.name }.sorted()
                        .map { Suggestion(it, "suggestOwner") }.startingWith(typed)
                }

                else -> emptyList()
            }
        }
    }

    private val LANGUAGES: List<String> by lazy {
        val fromJava = Locale.getAvailableLocales()
            .map { it.language.lowercase(Locale.ROOT) }
        val fromConfig = VanillaServerState.config.languages.keys
            .map { it.trim().lowercase(Locale.ROOT).substringBefore('_') }
        (fromJava + fromConfig)
            .filter { it.matches(Regex("^[a-z]{2}$")) }
            .map { if (it == "uk") "ua" else it }
            .distinct()
            .sorted()
    }
}
