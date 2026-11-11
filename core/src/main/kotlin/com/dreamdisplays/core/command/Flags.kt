package com.dreamdisplays.core.command

/**
 * The flags of `/display fullscreen start` and `/display prepare start`: any subset, in any order, each at most once.
 * [Flag.tooltip] is the lang key shown beside its name on tab.
 */
class Flag(val name: String, val value: Value, val tooltip: String, val permission: String? = null) {
    sealed class Value {
        /** A bare switch. */
        data object None : Value()

        /** One word, suggested by [suggest] (a platform suggestion name). */
        data class Word(val suggest: String) : Value()

        /** A number within [min]..[max], suggested by [suggest]. */
        data class Number(val min: Double, val max: Double, val suggest: String) : Value()

        /** One of [choices] (value -> tooltip key). */
        data class Choice(val choices: Map<String, String>) : Value()

        /** A radius in blocks, optionally followed by an `x y z` center. */
        data object Radius : Value()
    }
}

object Flags {
    val START: List<Flag> = listOf(
        Flag("server", Flag.Value.Word("servers"), "suggestFlagServer", Perm.FULLSCREEN_NETWORK),
        Flag("target", Flag.Value.Word("players"), "suggestFlagTarget"),
        Flag("radius", Flag.Value.Radius, "suggestFlagRadius"),
        Flag(
            "view", Flag.Value.Choice(linkedMapOf("standard" to "suggestModeStandard", "immersive" to "suggestModeImmersive")),
            "suggestFlagMode",
        ),
        Flag("forced", Flag.Value.None, "suggestFlagForced"),
        Flag("transient", Flag.Value.None, "suggestFlagTransient"),
        Flag("volume", Flag.Value.Number(0.0, 200.0, "volumes"), "suggestFlagVolume"),
        Flag("loop", Flag.Value.None, "suggestFlagLooped"),
        Flag("quality", Flag.Value.Word("qualities"), "suggestFlagQuality"),
    )

    val PREPARE: List<Flag> = listOf(
        Flag("target", Flag.Value.Word("players"), "suggestFlagTarget"),
        Flag("radius", Flag.Value.Radius, "suggestFlagRadius"),
        Flag("quality", Flag.Value.Word("qualities"), "suggestFlagQuality"),
        Flag("rewind", Flag.Value.None, "suggestFlagRewind"),
    )

    /** Parsed flags: name -> its value words (empty for a switch). */
    class Parsed(val values: Map<String, List<String>>) {
        fun has(name: String): Boolean = name in values
        fun word(name: String): String? = values[name]?.firstOrNull()
        fun number(name: String): Double? = values[name]?.firstOrNull()?.toDoubleOrNull()
        fun numbers(name: String): List<Double> = values[name].orEmpty().mapNotNull { it.toDoubleOrNull() }
    }

    sealed class Result {
        class Ok(val flags: Parsed) : Result()

        /** [key] is a lang key formatted with [args]. */
        class Error(val key: String, val args: List<String>) : Result()
    }

    /** Parses [text] against [spec]; flags the sender may not use ([allowed]) count as unknown. */
    fun parse(spec: List<Flag>, text: String?, allowed: (String) -> Boolean): Result {
        val words = text.orEmpty().trim().split(' ').filter { it.isNotEmpty() }
        val usable = spec.filter { f -> f.permission?.let(allowed) ?: true }
        val values = linkedMapOf<String, List<String>>()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val flag = usable.firstOrNull { it.name.equals(word, ignoreCase = true) }
                ?: return Result.Error("cmd.error.flagUnknown", listOf(word, usable.joinToString(", ") { it.name }))
            if (flag.name in values) return Result.Error("cmd.error.flagTwice", listOf(flag.name))
            i++
            when (val v = flag.value) {
                Flag.Value.None -> values[flag.name] = emptyList()
                is Flag.Value.Word -> {
                    val value = words.getOrNull(i) ?: return missing(flag)
                    values[flag.name] = listOf(value)
                    i++
                }

                is Flag.Value.Number -> {
                    val value = words.getOrNull(i) ?: return missing(flag)
                    val n = value.toDoubleOrNull()
                    if (n == null || n < v.min || n > v.max) {
                        return Result.Error("cmd.error.flagNumber", listOf(flag.name, fmt(v.min), fmt(v.max), value))
                    }
                    values[flag.name] = listOf(value)
                    i++
                }

                is Flag.Value.Choice -> {
                    val value = words.getOrNull(i) ?: return missing(flag)
                    val choice = v.choices.keys.firstOrNull { it.equals(value, ignoreCase = true) }
                        ?: return Result.Error("cmd.error.flagValue", listOf(flag.name, v.choices.keys.joinToString("|")))
                    values[flag.name] = listOf(choice)
                    i++
                }

                Flag.Value.Radius -> {
                    val blocks = words.getOrNull(i) ?: return missing(flag)
                    val r = blocks.toDoubleOrNull()
                    if (r == null || r < 0) return Result.Error("cmd.error.flagNumber", listOf(flag.name, "0", "∞", blocks))
                    i++
                    val center = words.drop(i).take(3)
                    if (center.size == 3 && center.all { it.toDoubleOrNull() != null }) {
                        values[flag.name] = listOf(blocks) + center
                        i += 3
                    } else {
                        values[flag.name] = listOf(blocks)
                    }
                }
            }
        }
        return Result.Ok(Parsed(values))
    }

    /** What the word being typed at the end of [text] is: a flag name, or the [index]th value of [flag]. */
    sealed class Slot {
        class Name(val used: Set<String>) : Slot()
        class Value(val flag: Flag, val index: Int) : Slot()
    }

    /** The slot the last (possibly empty) word of [text] fills, or null when the text before it does not parse. */
    fun slot(spec: List<Flag>, text: String, allowed: (String) -> Boolean): Slot? {
        val done = text.substringBeforeLast(' ', "")
        val words = done.split(' ').filter { it.isNotEmpty() }
        val usable = spec.filter { f -> f.permission?.let(allowed) ?: true }
        val used = mutableSetOf<String>()
        var i = 0
        while (i < words.size) {
            val flag = usable.firstOrNull { it.name.equals(words[i], ignoreCase = true) } ?: return null
            used += flag.name
            i++
            val takes = when (flag.value) {
                Flag.Value.None -> 0
                Flag.Value.Radius -> {
                    // radius <blocks> then optionally x y z: while those are still being typed, they are its values
                    val rest = words.drop(i)
                    when {
                        rest.isEmpty() -> return Slot.Value(flag, 0)
                        rest.size in 1..3 && rest.all { it.toDoubleOrNull() != null } -> return Slot.Value(flag, rest.size)
                        rest.size >= 4 && rest.take(4).all { it.toDoubleOrNull() != null } -> 4
                        else -> 1
                    }
                }

                else -> 1
            }
            if (i + takes > words.size) return Slot.Value(flag, words.size - i)
            i += takes
        }
        return Slot.Name(used)
    }

    private fun missing(flag: Flag) = Result.Error("cmd.error.flagMissing", listOf(flag.name))

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
