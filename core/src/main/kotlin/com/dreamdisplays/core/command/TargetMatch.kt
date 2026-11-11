package com.dreamdisplays.core.command

import java.util.*

/**
 * How every `/display` command reads a display token: `this` is the display the player looks at; anything else is a
 * full id, a name (case-insensitive), or a unique id prefix of at least [MIN_PREFIX] characters, in that order.
 */
object TargetMatch {
    const val THIS = "this"
    const val ALL = "all"
    const val MIN_PREFIX = 4

    /** One display as far as matching is concerned. */
    data class Candidate(val id: UUID, val name: String?) {
        val label: String get() = name ?: id.toString().take(8)
    }

    sealed class Result {
        /** The token is `this`: the caller resolves it from where the player looks. */
        data object LookedAt : Result()

        data class Found(val id: UUID) : Result()

        data object NotFound : Result()

        /** A prefix shared by several displays; [labels] lists them. */
        data class Ambiguous(val labels: List<String>) : Result()
    }

    fun isThis(token: String?): Boolean = token == null || token.equals(THIS, ignoreCase = true)

    /**
     * True when naming a display [name] would hide display [id]: [match] tries names before id prefixes, so commands
     * aimed at [id]'s listed short id would reach the named display instead.
     */
    fun nameShadowsId(name: String, id: UUID): Boolean {
        val n = name.trim()
        return n.length >= MIN_PREFIX && id.toString().startsWith(n, ignoreCase = true)
    }

    fun match(token: String?, displays: Collection<Candidate>): Result {
        if (isThis(token)) return Result.LookedAt
        val t = token!!.trim()
        runCatching { UUID.fromString(t) }.getOrNull()?.let { id ->
            return if (displays.any { it.id == id }) Result.Found(id) else Result.NotFound
        }
        displays.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { named ->
            // A name wins over an id prefix, but never over the short id an unnamed display is listed and completed by
            // (a display named after it would silently take that display's place)
            val shadowed = displays.filter { it.id != named.id && it.name == null && it.label.equals(t, ignoreCase = true) }
            return if (shadowed.isEmpty()) Result.Found(named.id)
            else Result.Ambiguous((shadowed + named).map { it.label }.sorted())
        }
        if (t.length < MIN_PREFIX) return Result.NotFound
        val matches = displays.filter { it.id.toString().startsWith(t, ignoreCase = true) }
        return when (matches.size) {
            0 -> Result.NotFound
            1 -> Result.Found(matches[0].id)
            else -> Result.Ambiguous(matches.map { it.label }.sorted())
        }
    }
}
