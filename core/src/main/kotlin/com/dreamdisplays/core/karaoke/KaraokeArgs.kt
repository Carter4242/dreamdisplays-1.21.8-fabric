package com.dreamdisplays.core.karaoke

/** One `/display karaoke <display> ...` action, parsed by [KaraokeArgs.parse]. */
sealed interface KaraokeAction {
    /** Needs the operator permission (everything but joining, leaving and viewing the line). */
    val operatorOnly: Boolean get() = true

    data class Toggle(val on: Boolean) : KaraokeAction
    data object Status : KaraokeAction
    data class Trim(val ms: Long) : KaraokeAction
    data class SingerAdd(val player: String, val leadMs: Long?) : KaraokeAction
    data class SingerRemove(val player: String) : KaraokeAction
    data class SingerTrim(val player: String, val ms: Long) : KaraokeAction
    data class Join(val url: String) : KaraokeAction {
        override val operatorOnly get() = false
    }

    data object Leave : KaraokeAction {
        override val operatorOnly get() = false
    }

    data object Line : KaraokeAction {
        override val operatorOnly get() = false
    }

    data class LineAdd(val player: String, val url: String) : KaraokeAction
    data class LineRemove(val player: String) : KaraokeAction
    data object LineClear : KaraokeAction

    /** Unparseable input; [reason] says which. */
    data class Invalid(val reason: Reason) : KaraokeAction {
        override val operatorOnly get() = false
    }

    enum class Reason { USAGE, BAD_LEAD, BAD_TRIM }
}

/** Parses the words after `/display karaoke <display>`. */
object KaraokeArgs {
    const val MAX_LEAD_MS = 3_000L
    const val MAX_TRIM_MS = 2_000L

    fun parse(args: List<String>): KaraokeAction {
        val words = args.filter { it.isNotBlank() }
        val usage = KaraokeAction.Invalid(KaraokeAction.Reason.USAGE)
        val first = words.firstOrNull()?.lowercase() ?: return KaraokeAction.Status
        return when (first) {
            "on" -> if (words.size == 1) KaraokeAction.Toggle(true) else usage
            "off" -> if (words.size == 1) KaraokeAction.Toggle(false) else usage
            "status" -> if (words.size == 1) KaraokeAction.Status else usage
            "trim" -> if (words.size == 2) trim(words[1]) { KaraokeAction.Trim(it) } else usage
            "join" -> if (words.size >= 2) KaraokeAction.Join(words.drop(1).joinToString(" ")) else usage
            "leave" -> if (words.size == 1) KaraokeAction.Leave else usage
            "singer" -> singer(words.drop(1)) ?: usage
            "line" -> line(words.drop(1)) ?: usage
            else -> usage
        }
    }

    private fun singer(rest: List<String>): KaraokeAction? {
        val op = rest.firstOrNull()?.lowercase() ?: return null
        val player = rest.getOrNull(1) ?: return null
        return when (op) {
            "add" -> when (rest.size) {
                2 -> KaraokeAction.SingerAdd(player, null)
                3 -> rest[2].toLongOrNull()?.takeIf { it in 0..MAX_LEAD_MS }?.let { KaraokeAction.SingerAdd(player, it) }
                    ?: KaraokeAction.Invalid(KaraokeAction.Reason.BAD_LEAD)

                else -> null
            }

            "remove" -> if (rest.size == 2) KaraokeAction.SingerRemove(player) else null
            "trim" -> if (rest.size == 3) trim(rest[2]) { KaraokeAction.SingerTrim(player, it) } else null
            else -> null
        }
    }

    private fun line(rest: List<String>): KaraokeAction? {
        val op = rest.firstOrNull()?.lowercase() ?: return KaraokeAction.Line
        return when (op) {
            "list" -> if (rest.size == 1) KaraokeAction.Line else null
            "add" -> if (rest.size >= 3) KaraokeAction.LineAdd(rest[1], rest.drop(2).joinToString(" ")) else null
            "remove" -> if (rest.size == 2) KaraokeAction.LineRemove(rest[1]) else null
            "clear" -> if (rest.size == 1) KaraokeAction.LineClear else null
            else -> null
        }
    }

    private inline fun trim(raw: String, build: (Long) -> KaraokeAction): KaraokeAction =
        raw.removePrefix("+").toLongOrNull()?.takeIf { it in -MAX_TRIM_MS..MAX_TRIM_MS }?.let(build)
            ?: KaraokeAction.Invalid(KaraokeAction.Reason.BAD_TRIM)
}
