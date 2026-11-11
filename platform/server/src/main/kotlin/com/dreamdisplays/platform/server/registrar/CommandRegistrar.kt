package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.core.command.Call
import com.dreamdisplays.core.command.ChatLine
import com.dreamdisplays.core.command.CommandPlatform
import com.dreamdisplays.core.command.DisplayCommandTree
import com.dreamdisplays.core.command.DisplayCommands
import com.dreamdisplays.core.command.Handler
import com.dreamdisplays.core.command.Kind
import com.dreamdisplays.core.command.Perm
import com.dreamdisplays.core.command.SuggestRequest
import com.dreamdisplays.core.command.Suggestion
import com.dreamdisplays.core.command.TargetMatch
import com.dreamdisplays.core.command.startingWith
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.commands.suggest.DisplaySuggestions
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.policy.ClientErrorReports
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.arnodoelinger.platformweaver.PaperOnly
//? if >=1.20.5 {
import io.papermc.paper.command.brigadier.CommandSourceStack
//?}
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * The `Paper` `/display` command: [DisplayCommands] compiled by [DisplayCommandTree], with a handler per command.
 * `VanillaCommandTree.kt` is the `Fabric` / `NeoForge` counterpart over the same spec.
 */
@PaperOnly
object CommandRegistrar {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    /** Builds the `/display` node to register. */
    fun buildDisplayCommand(): LiteralCommandNode<CommandSourceStack> = DisplayCommandTree(platform, handlers).root()

    private val platform = object : CommandPlatform<CommandSourceStack> {
        override fun has(source: CommandSourceStack, permission: String) = source.sender.hasPermission(permission)

        override fun isPlayer(source: CommandSourceStack) = source.sender is Player

        override fun text(source: CommandSourceStack, key: String, vararg args: String) =
            MessageUtil.formatIndexed(source.sender, key, *args)

        override fun send(source: CommandSourceStack, lines: List<ChatLine>) = lines.forEach { line ->
            var component = legacy.deserialize(line.text)
            line.hover?.let { component = component.hoverEvent(HoverEvent.showText(legacy.deserialize(it))) }
            when (val click = line.click) {
                is ChatLine.Click.Run -> component = component.clickEvent(ClickEvent.runCommand(click.command))
                is ChatLine.Click.Suggest -> component = component.clickEvent(ClickEvent.suggestCommand(click.command))
                null -> {}
            }
            MessageUtil.sendComponent(source.sender, component)
        }

        override fun suggest(source: CommandSourceStack, request: SuggestRequest<CommandSourceStack>): List<Suggestion> =
            suggestions(source, request)
    }

    private fun h(block: (CommandSender, Call<CommandSourceStack>) -> Unit) =
        Handler<CommandSourceStack> { call -> block(call.source.sender, call) }

    private val handlers: Map<String, Handler<CommandSourceStack>> = mapOf(
        "on" to h { s, c -> OnCommand().execute(s, (listOf<String?>("on") + listOfNotNull(c["player"])).toTypedArray()) },
        "off" to h { s, c -> OffCommand().execute(s, (listOf<String?>("off") + listOfNotNull(c["player"])).toTypedArray()) },

        "create" to h { s, _ -> CreateCommand().execute(s, emptyArray()) },
        "delete" to h { s, c -> DeleteCommand().execute(s, arrayOf<String?>(c.display)) },
        "info" to h { s, c -> InfoCommand().execute(s, arrayOf<String?>(c.display)) },
        "name" to h { s, c -> NameCommand().execute(s, arrayOf(c.display, c["name"])) },
        "list" to h { s, c -> list(s, null, null, c["page"]) },
        "list.mine" to h { s, c -> list(s, "mine", null, c["page"]) },
        "list.world" to h { s, c -> list(s, "world", c["world"], c["page"]) },
        "list.owner" to h { s, c -> list(s, "owner", c["owner"], c["page"]) },
        "list.mode" to h { s, c -> list(s, "mode", c["mode"], c["page"]) },

        "video" to h { s, c ->
            if (c["url"].equals(DisplayCommands.CLEAR_VIDEO, ignoreCase = true)) {
                val display = resolvePaperDisplay(s, c.display) as? PaperDisplayData ?: return@h
                clearDisplayVideo(s as Player, display)
            } else {
                VideoCommand().execute(s, arrayOf(c.display, c["url"], c["lang"] ?: ""))
            }
        },
        "speed" to h { s, c -> SpeedCommand.execute(s, c.display, c["rate"]) },
        "mode" to h { s, c -> ModeCommand.execute(s, c.display, c["mode"]) },
        "stats" to h { s, c ->
            if (c.display.equals(TargetMatch.ALL, ignoreCase = true)) ViewerStatsCommand.executeAll(s)
            else ViewerStatsCommand.execute(s, c.display)
        },

        "schedule.show" to h { s, c -> ScheduleCommand().execute(s, arrayOf(c.display, null, null)) },
        "schedule.play" to h { s, c -> ScheduleCommand().execute(s, arrayOf(c.display, "play", c["time"])) },
        "schedule.pause" to h { s, c -> ScheduleCommand().execute(s, arrayOf(c.display, "pause", c["time"])) },
        "schedule.cancel" to h { s, c -> ScheduleCommand().execute(s, arrayOf(c.display, "cancel", null)) },

        "queue.list" to h { s, c -> QueueCommand.execute(s, c.display, "list", emptyList()) },
        "queue.add" to h { s, c -> QueueCommand.execute(s, c.display, "add", listOfNotNull(c["url"])) },
        "queue.remove" to h { s, c -> QueueCommand.execute(s, c.display, "remove", listOfNotNull(c["n"])) },
        "queue.move" to h { s, c -> QueueCommand.execute(s, c.display, "move", listOfNotNull(c["n"], c["to"])) },
        "queue.skip" to h { s, c -> QueueCommand.execute(s, c.display, "skip", emptyList()) },
        "queue.clear" to h { s, c -> QueueCommand.execute(s, c.display, "clear", emptyList()) },
        // Without on|off it shows the queue, whose header carries the loop state
        "queue.loop" to h { s, c ->
            val state = c["state"]
            QueueCommand.execute(s, c.display, if (state == null) "list" else "loop", listOfNotNull(state))
        },

        "karaoke.status" to karaoke("status"),
        "karaoke.stop" to karaoke("stop"),
        "karaoke.trim" to karaoke("trim", "@ms"),
        "karaoke.singer.add" to karaoke("singer", "add", "@player", "@duet", "@lead"),
        "karaoke.singer.remove" to karaoke("singer", "remove", "@singer"),
        "karaoke.singer.trim" to karaoke("singer", "trim", "@singer", "@ms"),
        "karaoke.join" to karaoke("join", "@url"),
        "karaoke.leave" to karaoke("leave"),
        "karaoke.line.list" to karaoke("line"),
        "karaoke.line.add" to karaoke("line", "add", "@player", "@url"),
        "karaoke.line.remove" to karaoke("line", "remove", "@singer"),
        "karaoke.line.clear" to karaoke("line", "clear"),

        "prepare.start" to h { s, c ->
            val id = sourceToken(s, c["source"]!!, network = false) ?: return@h
            PaperFullscreenCommand.prepare(s, c.source.location, id, c.flags!!.prepareOptions())
        },
        "prepare.list" to h { s, _ -> PaperFullscreenCommand.list(s) },
        "prepare.cancel" to h { s, c -> PaperFullscreenCommand.stop(s, sessionToken(c["id"]!!)) },
        "fullscreen.start" to h { s, c ->
            val flags = c.flags!!
            val id = sourceToken(s, c["source"]!!, network = flags.has("server")) ?: return@h
            PaperFullscreenCommand.start(s, c.source.location, id, flags.startOptions())
        },
        "fullscreen.list" to h { s, _ -> PaperFullscreenCommand.list(s) },
        "fullscreen.stop" to h { s, c -> PaperFullscreenCommand.stop(s, sessionToken(c["id"]!!)) },

        "share.stop" to h { s, c -> ShareCommand.stop(s, c.display) },
        "share.list" to h { s, _ -> ShareCommand.list(s) },

        "config" to h { s, c -> ConfigCommand.execute(s, c["key"], c["value"]) },
        "policy" to h { s, c -> PolicyCommand.execute(s, c.display, c["key"], c["value"]) },
        "reload" to h { s, _ -> ReloadCommand().execute(s, emptyArray()) },

        "diag.clients" to h { s, _ ->
            StatsCommand().execute(s, emptyArray())
            ClientsCommand.execute(s)
        },
        "diag.env" to h { s, c -> ErrorsCommand.diag(s, c["player"]!!) },
        "diag.loglevel" to h { s, c -> DebugLevelCommand.execute(s, c["player"]!!, c["level"]) },
        "diag.errors" to h { s, c -> ErrorsCommand.execute(s, listOfNotNull(c["who"], c["type"], c["count"])) },
    )

    private fun list(sender: CommandSender, filter: String?, value: String?, page: String?) =
        ListCommand().execute(sender, (listOf<String?>("list") + listOfNotNull(filter, value, page)).toTypedArray())

    /** Hands [KaraokeCommand] the [words] in its order; `@name` stands for that argument's value (left out when absent). */
    private fun karaoke(vararg words: String) = h { s, c ->
        val args = words.mapNotNull { word -> if (word.startsWith("@")) c[word.substring(1)] else word }
        KaraokeCommand.execute(s, c.display, args)
    }

    /**
     * The id `fullscreen`/`prepare` start get for [token]: a URL as typed, else the display's full id. A network
     * broadcast passes ids as typed too, since the display may live on another backend.
     */
    private fun sourceToken(sender: CommandSender, token: String, network: Boolean): String? {
        if (network || token.contains("://")) return token
        return resolvePaperDisplay(sender, token)?.id?.toString()
    }

    /** A session or preparation id, `all`, or a display named any way, as `PaperFullscreenCommand.stop` takes it. */
    private fun sessionToken(token: String): String {
        if (token.equals(TargetMatch.ALL, ignoreCase = true)) return token
        if (FullscreenCommand.stopSuggestions().any { it.equals(token, ignoreCase = true) }) return token
        return findDisplayQuietly(token)?.id?.toString() ?: token
    }

    private fun suggestions(source: CommandSourceStack, r: SuggestRequest<CommandSourceStack>): List<Suggestion> {
        val sender = source.sender
        val typed = r.typed
        val values = r.call.values
        DisplaySuggestions.common(r.def, r.name, r.kind, typed, values)?.let { common ->
            return when {
                r.def.id == "share.stop" -> (ShareCommand.sharedDisplaySuggestions().map { Suggestion(it) } + common)
                    .distinctBy { it.text }.startingWith(typed)

                else -> common
            }
        }
        val online = PaperFullscreenCommand.onlinePlayerNames()
        return when (r.kind) {
            Kind.PLAYER -> if (r.def.id == "on" || r.def.id == "off") {
                if (sender.hasPermission(Perm.VIEWING_TOGGLE_OTHERS)) DisplaySuggestions.players(online, typed) else emptyList()
            } else DisplaySuggestions.players(online, typed)

            Kind.KNOWN_PLAYER -> DisplaySuggestions.players((online + ClientErrorReports.knownPlayerNames()).distinct(), typed)
            Kind.LANG -> VideoCommand.languageSuggestions.map { Suggestion(it, "suggestLanguage") }.startingWith(typed)
            Kind.TIME -> DisplaySuggestions.scheduleTimes(
                (sender as? Player)?.let { ScheduleTimeUtil.offsetMinutesOf(it.uniqueId) } ?: 0, typed,
            )

            Kind.POSITIVE -> QueueCommand.positions(values["display"] ?: TargetMatch.THIS).map { Suggestion(it) }.startingWith(typed)
            else -> when (r.name) {
                "players" -> DisplaySuggestions.playerList(online, typed)
                "x" -> DisplaySuggestions.coordinate(source.location.x, "x", typed)
                "y" -> DisplaySuggestions.coordinate(source.location.y, "y", typed)
                "z" -> DisplaySuggestions.coordinate(source.location.z, "z", typed)
                "world" -> Bukkit.getWorlds().map { Suggestion(it.name, "suggestWorld") }.startingWith(typed)
                "owner" -> DisplayManager.getDisplays().mapNotNull { Bukkit.getOfflinePlayer(it.ownerId).name }.distinct()
                    .sorted().map { Suggestion(it, "suggestOwner") }.startingWith(typed)

                "rate" -> SpeedCommand.valueSuggestions().map { Suggestion(it) }.startingWith(typed)
                "singer" -> DisplaySuggestions.players(
                    (KaraokeCommand.knownNames(values["display"] ?: TargetMatch.THIS) + online).distinct(), typed,
                )

                "key" -> (if (r.def.id == "config") ConfigCommand.keySuggestions() else PolicyCommand.keySuggestions())
                    .map { Suggestion(it) }.startingWith(typed)

                "value" -> {
                    val key = values["key"] ?: return emptyList()
                    (if (r.def.id == "config") ConfigCommand.valueSuggestions(key) else PolicyCommand.valueSuggestions(key))
                        .map { Suggestion(it) }.startingWith(typed)
                }

                "level" -> DebugLevelCommand.levelSuggestions().map { Suggestion(it) }.startingWith(typed)
                "who" -> ErrorsCommand.targetSuggestions().map { Suggestion(it) }.startingWith(typed)
                "type" -> ErrorsCommand.optionSuggestions().map { Suggestion(it) }.startingWith(typed)
                else -> emptyList()
            }
        }
    }
}
