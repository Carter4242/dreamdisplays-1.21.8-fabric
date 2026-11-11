package com.dreamdisplays.core.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import java.util.concurrent.CompletableFuture
import kotlin.math.min

/** A chat line: text with `&` colour codes, an optional hover text and an optional click. */
data class ChatLine(val text: String, val hover: String? = null, val click: Click? = null) {
    sealed class Click {
        abstract val command: String

        /** Runs [command] at once. */
        data class Run(override val command: String) : Click()

        /** Puts [command] in the chat box. */
        data class Suggest(override val command: String) : Click()
    }
}

/** What `/display` needs from the server it runs on. */
interface CommandPlatform<S> {
    fun has(source: S, permission: String): Boolean

    fun isPlayer(source: S): Boolean

    /** The [key] lang string in [source]'s language, `{0}`, `{1}`... filled from [args]; the key when missing. */
    fun text(source: S, key: String, vararg args: String): String

    fun send(source: S, lines: List<ChatLine>)

    /** Values for [request]'s slot; [SuggestRequest.typed] is what's typed of it so far. */
    fun suggest(source: S, request: SuggestRequest<S>): List<Suggestion>
}

/**
 * A suggestion slot: argument [name] (or, inside flags, the flag value's suggestion name) of [def], of [kind] (null
 * inside flags), with the values typed before it in [call].
 */
class SuggestRequest<S>(val def: CommandDef, val name: String, val kind: Kind?, val typed: String, val call: Call<S>)

/** One run of [def] by [source] with the typed argument [values] (by argument name) and parsed [flags]. */
class Call<S>(
    val source: S,
    val def: CommandDef,
    val values: Map<String, String>,
    val flags: Flags.Parsed?,
    val context: CommandContext<S>?,
) {
    operator fun get(name: String): String? = values[name]

    /** The display token, `this` when it was left off. */
    val display: String get() = values["display"] ?: "this"

    fun int(name: String): Int? = values[name]?.toIntOrNull()
}

fun interface Handler<S> {
    fun run(call: Call<S>)
}

/**
 * Compiles [DisplayCommands] into the `/display` Brigadier tree for one platform. Commands without a handler on this
 * platform are left out. Answers help, usage errors, "moved" hints for old forms and every tab completion.
 */
class DisplayCommandTree<S>(
    private val platform: CommandPlatform<S>,
    private val handlers: Map<String, Handler<S>>,
    all: List<CommandDef> = DisplayCommands.ALL,
) {
    /** The commands this platform runs, plus help-only lines of commands it runs. */
    val defs: List<CommandDef> = all.filter { def ->
        def.id == "help" || (if (def.helpOnly) all.any { it.path == def.path && !it.helpOnly && it.id in handlers }
        else def.id in handlers)
    }
    private val runnable = defs.filter { !it.helpOnly }

    private val inner = CommandDispatcher<S>()

    init {
        val trie = Trie()
        runnable.forEach { def -> trie.add(def) }
        trie.children.values.forEach { inner.root.addChild(literal(it, listOf(it.word)).build()) }
    }

    /** True when [source] may run [def]. */
    fun allowed(source: S, def: CommandDef): Boolean = def.permission == null || platform.has(source, def.permission)

    /** True when [source] may run anything, which is when `/display` exists for it at all. */
    fun visible(source: S): Boolean = runnable.any { it.permission != null && allowed(source, it) }

    /**
     * The `/display` node to register. The client only ever sees `/display <command>` and asks the server for every
     * completion, so no custom argument type reaches it.
     */
    fun root(): LiteralCommandNode<S> =
        LiteralArgumentBuilder.literal<S>("display")
            .requires { visible(it) }
            .executes { ctx -> help(ctx.source, null); 1 }
            .then(
                RequiredArgumentBuilder.argument<S, String>("command", StringArgumentType.greedyString())
                    .suggests { ctx, builder -> complete(ctx.source, builder) }
                    .executes { ctx -> execute(ctx.source, StringArgumentType.getString(ctx, "command")); 1 }
            ).build()

    /** Runs [input] (what follows `/display `) for [source]. */
    fun execute(source: S, input: String) {
        val words = input.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return help(source, null)
        MovedHints.hint(words)?.let { moved ->
            return platform.send(
                source, listOf(ChatLine(platform.text(source, "cmd.error.moved", moved), click = ChatLine.Click.Suggest(moved))),
            )
        }
        val parse = inner.parse(input.trim(), source)
        try {
            inner.execute(parse)
        } catch (_: CommandSyntaxException) {
            usage(source, words)
        }
    }

    /** Tab completions of [input] (what follows `/display `), as plain texts; for tests. */
    fun completions(source: S, input: String): List<String> =
        complete(source, SuggestionsBuilder(input, input.lowercase(), 0)).join().list.map { it.text }

    // ---------------------------------------------------------------- help

    /** `/display help [area]`: the overview, or one area's (or command word's) commands. */
    fun help(source: S, topic: String?) {
        if (topic == null) return platform.send(source, overview(source))
        val area = Area.byId(topic)
        val lines = when {
            area != null -> areaHelp(source, area)
            else -> prefixHelp(source, listOf(topic.lowercase()))
        }
        if (lines == null) {
            return platform.send(source, listOf(ChatLine(platform.text(source, "cmd.error.unknownTopic", topic))) + overview(source))
        }
        platform.send(source, lines)
    }

    /** The overview: one line per area [source] can use, listing its commands. */
    fun overview(source: S): List<ChatLine> {
        val lines = mutableListOf(ChatLine(platform.text(source, "cmd.help.header")))
        Area.entries.forEach { area ->
            val words = helpDefs(source).filter { it.area == area }
                .map { if (area.prefix) it.path[1] else it.path[0] }.distinct()
            if (words.isEmpty()) return@forEach
            lines += ChatLine(
                platform.text(source, "cmd.help.areaLine", area.id, words.joinToString("  ")),
                hover = platform.text(source, area.key),
                click = ChatLine.Click.Run("/display help ${area.id}"),
            )
        }
        lines += ChatLine(platform.text(source, "cmd.help.target"))
        return lines
    }

    /** One area's commands, or null when [source] can use none of them. */
    fun areaHelp(source: S, area: Area): List<ChatLine>? {
        val defs = helpDefs(source).filter { it.area == area }
        if (defs.isEmpty()) return null
        return listOf(ChatLine(platform.text(source, "cmd.help.areaHeader", platform.text(source, area.key)))) +
                commandLines(source, defs) + targetLine(source, defs)
    }

    /** The commands under the words [prefix] that [source] can use, or null when none. */
    private fun prefixHelp(source: S, prefix: List<String>): List<ChatLine>? {
        if (prefix.size == 1) Area.byId(prefix[0])?.takeIf { it.prefix }?.let { return areaHelp(source, it) }
        val defs = helpDefs(source).filter { it.path.take(prefix.size) == prefix }
        if (defs.isEmpty()) return null
        return listOf(ChatLine(platform.text(source, "cmd.help.commands"))) + commandLines(source, defs) + targetLine(source, defs)
    }

    private fun helpDefs(source: S): List<CommandDef> = defs.filter { it.inHelp && it.area != null && allowed(source, it) }

    private fun commandLines(source: S, defs: List<CommandDef>): List<ChatLine> = defs.map { def ->
        val description = platform.text(source, def.key)
        ChatLine(
            platform.text(source, "cmd.help.line", def.command, if (def.argUsage.isEmpty()) "" else " " + def.argUsage, description),
            hover = description,
            click = ChatLine.Click.Suggest(def.command + " "),
        )
    }

    private fun targetLine(source: S, defs: List<CommandDef>): List<ChatLine> =
        if (defs.any { it.takesDisplay }) listOf(ChatLine(platform.text(source, "cmd.help.target"))) else emptyList()

    // ---------------------------------------------------------------- errors

    /** Why [words] didn't run: the usage of the closest commands, an unknown word, or a missing permission. */
    private fun usage(source: S, words: List<String>) {
        val lower = words.map { it.lowercase() }
        var matched = emptyList<String>()
        for (n in 1..lower.size) {
            val prefix = lower.take(n)
            if (runnable.any { it.path.size >= n && it.path.take(n) == prefix }) matched = prefix else break
        }
        if (matched.isEmpty()) {
            return platform.send(source, listOf(ChatLine(platform.text(source, "cmd.error.unknown", words[0]))))
        }
        val under = defs.filter { it.path.take(matched.size) == matched && it.id != "help" }
        val usable = under.filter { allowed(source, it) }
        if (usable.isEmpty()) {
            return platform.send(
                source, listOf(ChatLine(platform.text(source, "cmd.error.permission", matched.joinToString(" ")))),
            )
        }
        val shown = usable.filter { it.inHelp || it.path == matched }
        platform.send(source, listOf(ChatLine(platform.text(source, "cmd.error.usage"))) + commandLines(source, shown))
    }

    // ---------------------------------------------------------------- tree

    private inner class Trie(val word: String = "") {
        val children = linkedMapOf<String, Trie>()
        var def: CommandDef? = null

        fun add(def: CommandDef, depth: Int = 0) {
            if (depth == def.path.size) {
                check(this.def == null) { "two commands at /display ${def.path.joinToString(" ")}" }
                this.def = def
                return
            }
            children.getOrPut(def.path[depth]) { Trie(def.path[depth]) }.add(def, depth + 1)
        }

        fun all(): List<CommandDef> = listOfNotNull(def) + children.values.flatMap { it.all() }
    }

    private fun literal(node: Trie, prefix: List<String>): LiteralArgumentBuilder<S> {
        val def = node.def
        val beneath = node.all()
        val tooltipKey = def?.key ?: Area.byId(prefix.joinToString("."))?.takeIf { prefix.size == 1 }?.key
            ?: "cmd." + prefix.joinToString(".")
        val builder = TooltipLiteralBuilder<S>(node.word) { platform.text(it, tooltipKey) }
        builder.requires { source -> beneath.any { allowed(source, it) } }
        if (def == null) {
            builder.executes { ctx ->
                val lines = prefixHelp(ctx.source, prefix)
                if (lines == null) usage(ctx.source, prefix) else platform.send(ctx.source, lines)
                1
            }
        } else {
            attachArgs(builder, def)
        }
        node.children.values.forEach { builder.then(literal(it, prefix + it.word).build()) }
        return builder
    }

    private fun attachArgs(literal: LiteralArgumentBuilder<S>, def: CommandDef) {
        val args = def.args
        if (args.isEmpty() || args[0].optional) literal.executes { ctx -> run(def, ctx); 1 }
        var next: ArgumentBuilder<S, *>? = null
        for (i in args.indices.reversed()) {
            val arg = args[i]
            val builder = RequiredArgumentBuilder.argument<S, Any>(arg.name, type(arg.kind))
            builder.suggests { ctx, sb -> suggestArg(def, arg, ctx, sb) }
            if (args.drop(i + 1).all { it.optional }) builder.executes { ctx -> run(def, ctx); 1 }
            next?.let { builder.then(it) }
            next = builder
        }
        next?.let { literal.then(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun type(kind: Kind): ArgumentType<Any> = when (kind) {
        Kind.POSITIVE -> IntegerArgumentType.integer(1)
        Kind.REST, Kind.START_FLAGS, Kind.PREPARE_FLAGS -> StringArgumentType.greedyString()
        else -> BareTokenArgumentType
    } as ArgumentType<Any>

    private fun values(def: CommandDef, ctx: CommandContext<S>): Map<String, String> =
        def.args.mapNotNull { arg ->
            runCatching { ctx.getArgument(arg.name, Any::class.java) }.getOrNull()?.let { arg.name to it.toString() }
        }.toMap()

    private fun run(def: CommandDef, ctx: CommandContext<S>) {
        val source = ctx.source
        if (def.id == "help") return help(source, values(def, ctx)["area"])
        if (def.playerOnly && !platform.isPlayer(source)) {
            return platform.send(source, listOf(ChatLine(platform.text(source, "cmd.error.playersOnly", def.command))))
        }
        val values = values(def, ctx)
        val flagSpec = flagSpec(def)
        val flags = if (flagSpec == null) null else {
            when (val result = Flags.parse(flagSpec, values["flags"]) { platform.has(source, it) }) {
                is Flags.Result.Ok -> result.flags
                is Flags.Result.Error -> return platform.send(
                    source, listOf(ChatLine(platform.text(source, result.key, *result.args.toTypedArray()))),
                )
            }
        }
        handlers.getValue(def.id).run(Call(source, def, values, flags, ctx))
    }

    private fun flagSpec(def: CommandDef): List<Flag>? = when (def.args.lastOrNull()?.kind) {
        Kind.START_FLAGS -> Flags.START
        Kind.PREPARE_FLAGS -> Flags.PREPARE
        else -> null
    }

    // ---------------------------------------------------------------- completion

    private fun suggestArg(def: CommandDef, arg: ArgDef, ctx: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val source = ctx.source
        val call = Call(source, def, values(def, ctx), null, ctx)
        val spec = flagSpec(def)
        if (spec != null && arg.name == "flags") return suggestFlags(def, spec, call, builder)
        // The whole remaining text is this one argument (a token, or the rest of the line)
        val items = platform.suggest(source, SuggestRequest(def, arg.name, arg.kind, builder.remaining, call))
        return builder.reply(items, charArrayOf()) { tooltip(source, it) }
    }

    private fun suggestFlags(
        def: CommandDef,
        spec: List<Flag>,
        call: Call<S>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val source = call.source
        val text = builder.remaining
        val allowed = { perm: String -> platform.has(source, perm) }
        val usable = spec.filter { f -> f.permission?.let(allowed) ?: true }
        val slot = Flags.slot(spec, text, allowed) ?: return Suggestions.empty()
        val typed = typedWord(text)
        fun names(used: Set<String>) = usable.filter { it.name !in used }.map { Suggestion(it.name, it.tooltip) }.startingWith(typed)
        fun ask(name: String, typed: String) = platform.suggest(source, SuggestRequest(def, name, null, typed, call))
        val items: List<Suggestion> = when (slot) {
            is Flags.Slot.Name -> names(slot.used)
            is Flags.Slot.Value -> when (val v = slot.flag.value) {
                Flag.Value.None -> emptyList()
                is Flag.Value.Word -> if (v.suggest == "players") {
                    return builder.reply(ask("players", typedWord(text, charArrayOf(' ', ','))), charArrayOf(' ', ',')) {
                        tooltip(source, it)
                    }
                } else ask(v.suggest, typed)

                is Flag.Value.Number -> ask(v.suggest, typed)
                is Flag.Value.Choice -> v.choices.map { Suggestion(it.key, it.value) }.startingWith(typed)
                Flag.Value.Radius -> when (slot.index) {
                    0 -> ask("radii", typed)
                    1 -> ask("x", typed) + names(usedFlags(spec, text))
                    2 -> ask("y", typed)
                    else -> ask("z", typed)
                }
            }
        }
        return builder.reply(items) { tooltip(source, it) }
    }

    private fun usedFlags(spec: List<Flag>, text: String): Set<String> {
        val words = text.split(' ').map { it.lowercase() }.toSet()
        return spec.map { it.name }.filter { it in words }.toSet()
    }

    private fun tooltip(source: S, s: Suggestion): String? = s.tooltip?.let { platform.text(source, it, *s.args.toTypedArray()) }

    /** Completions of the greedy `command` argument, answered by the inner tree. */
    private fun complete(source: S, outer: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val input = outer.input
        val parse = inner.parse(readerAt(input, outer.start), source)
        val at = runCatching { parse.context.findSuggestionContext(input.length) }.getOrNull() ?: return Suggestions.empty()
        val start = min(at.startPos, input.length)
        val context = parse.context.build(input)
        val futures = at.parent.children.filter { it.canUse(source) }.map { node ->
            try {
                node.listSuggestions(context, SuggestionsBuilder(input, input.lowercase(), start))
            } catch (_: CommandSyntaxException) {
                Suggestions.empty()
            }
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { merge(input, futures.map { it.join() }) }
    }

    private fun merge(input: String, parts: List<Suggestions>): Suggestions {
        val seen = HashSet<String>()
        val entries = parts.flatMap { it.list }.filter { seen.add(it.text) }
        if (entries.isEmpty()) return Suggestions(StringRange.at(input.length), emptyList())
        val range = entries.map { it.range }.reduce(StringRange::encompassing)
        return Suggestions(range, entries.map { it.expand(input, range) })
    }
}
