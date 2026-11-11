package com.dreamdisplays.core.karaoke

/** One `/display karaoke <action> <display> ...` action, parsed by [KaraokeArgs.parse]. */
sealed interface KaraokeAction {
    /** Needs the operator permission (everything but joining, leaving and viewing the line). */
    val operatorOnly: Boolean get() = true

    /** Ends karaoke on the display at once: every singer stops and the line is cleared (`stop`, or `off`). */
    data object Stop : KaraokeAction
    data object Status : KaraokeAction
    data class Trim(val ms: Long) : KaraokeAction
    /** [duet]: sing alongside the current singers; otherwise [player] replaces them. */
    data class SingerAdd(val player: String, val leadMs: Long?, val duet: Boolean = false) : KaraokeAction
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

/** Parses a karaoke action's words (the action first, the display left out), as `/display karaoke` hands them over. */
object KaraokeArgs {
    const val MAX_LEAD_MS = 3_000L
    const val MAX_TRIM_MS = 2_000L

    fun parse(args: List<String>): KaraokeAction {
        val words = args.filter { it.isNotBlank() }
        val usage = KaraokeAction.Invalid(KaraokeAction.Reason.USAGE)
        val first = words.firstOrNull()?.lowercase() ?: return KaraokeAction.Status
        return when (first) {
            // There is no "on": a display is in karaoke exactly while someone sings there
            "stop", "off" -> if (words.size == 1) KaraokeAction.Stop else usage
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
            "add" -> {
                val duet = rest.getOrNull(2)?.equals("duet", ignoreCase = true) == true
                val tail = rest.drop(if (duet) 3 else 2)
                when (tail.size) {
                    0 -> KaraokeAction.SingerAdd(player, null, duet)
                    1 -> tail[0].toLongOrNull()?.takeIf { it in 0..MAX_LEAD_MS }?.let { KaraokeAction.SingerAdd(player, it, duet) }
                        ?: KaraokeAction.Invalid(KaraokeAction.Reason.BAD_LEAD)

                    else -> null
                }
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
