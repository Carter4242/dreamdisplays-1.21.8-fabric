package com.dreamdisplays.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.RedirectModifier
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate
import com.mojang.brigadier.suggestion.Suggestion as BrigadierSuggestion

/** One tab suggestion; [tooltip] is a lang key formatted with [args]. */
data class Suggestion(val text: String, val tooltip: String? = null, val args: List<String> = emptyList())

/** [this] without the entries that don't start with [typed] (case-insensitive). */
fun List<Suggestion>.startingWith(typed: String): List<Suggestion> =
    filter { it.text.startsWith(typed, ignoreCase = true) }

/**
 * Space-delimited unquoted token for display targets, selectors, times and URLs. It needs no registry entry and no wire
 * fallback: the `/display` tree is answered by the server, so this type never reaches a client.
 */
object BareTokenArgumentType : ArgumentType<String> {
    private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        if (reader.cursor == start) throw MISSING.create()
        return reader.string.substring(start, reader.cursor)
    }
}

/** Builds a [TooltipLiteralNode]. */
internal class TooltipLiteralBuilder<S>(
    private val literalName: String,
    private val tooltip: ((S) -> String)?,
) : LiteralArgumentBuilder<S>(literalName) {
    override fun getThis(): LiteralArgumentBuilder<S> = this

    override fun build(): LiteralCommandNode<S> {
        val result = TooltipLiteralNode(literalName, tooltip, command, requirement, redirect, redirectModifier, isFork)
        for (child in arguments) result.addChild(child)
        return result
    }
}

/** Literal that is only suggested to sources allowed to use it, with what [tooltip] gives shown beside its name. */
internal class TooltipLiteralNode<S>(
    private val literalName: String,
    private val tooltip: ((S) -> String)?,
    command: Command<S>?,
    requirement: Predicate<S>,
    redirect: CommandNode<S>?,
    modifier: RedirectModifier<S>?,
    forks: Boolean,
) : LiteralCommandNode<S>(literalName, command, requirement, redirect, modifier, forks) {
    override fun listSuggestions(context: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        if (!canUse(context.source)) return Suggestions.empty()
        if (!literalName.lowercase().startsWith(builder.remainingLowerCase)) return Suggestions.empty()
        val text = tooltip?.invoke(context.source) ?: return builder.suggest(literalName).buildFuture()
        return builder.suggest(literalName, LiteralMessage(text)).buildFuture()
    }

    override fun createBuilder(): LiteralArgumentBuilder<S> {
        val copy = TooltipLiteralBuilder(literalName, tooltip)
        copy.requires(requirement)
        command?.let { copy.executes(it) }
        redirect?.let { copy.forward(it, redirectModifier, isFork) }
        return copy
    }
}

/**
 * Replies [suggestions] for the word being typed at the end of [builder]'s remaining text; that word starts after the
 * last of [separators]. [tooltip] turns an entry into its tooltip text.
 */
internal fun SuggestionsBuilder.reply(
    suggestions: List<Suggestion>,
    separators: CharArray = charArrayOf(' '),
    tooltip: (Suggestion) -> String?,
): CompletableFuture<Suggestions> {
    if (suggestions.isEmpty()) return Suggestions.empty()
    val range = StringRange.between(start + remaining.lastIndexOfAny(separators) + 1, input.length)
    val entries = suggestions.map { BrigadierSuggestion(range, it.text, tooltip(it)?.let(::LiteralMessage)) }
    return CompletableFuture.completedFuture(Suggestions(range, entries))
}

/** The word being typed at the end of [text], after the last of [separators]. */
internal fun typedWord(text: String, separators: CharArray = charArrayOf(' ')): String =
    text.substring(text.lastIndexOfAny(separators) + 1)

internal fun readerAt(input: String, cursor: Int): StringReader = StringReader(input).also { it.cursor = cursor }
