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

    fun match(token: String?, displays: Collection<Candidate>): Result {
        if (isThis(token)) return Result.LookedAt
        val t = token!!.trim()
        runCatching { UUID.fromString(t) }.getOrNull()?.let { id ->
            return if (displays.any { it.id == id }) Result.Found(id) else Result.NotFound
        }
        displays.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return Result.Found(it.id) }
        if (t.length < MIN_PREFIX) return Result.NotFound
        val matches = displays.filter { it.id.toString().startsWith(t, ignoreCase = true) }
        return when (matches.size) {
            0 -> Result.NotFound
            1 -> Result.Found(matches[0].id)
            else -> Result.Ambiguous(matches.map { it.label }.sorted())
        }
    }
}
