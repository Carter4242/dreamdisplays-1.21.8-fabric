package com.dreamdisplays.core.command

/**
 * Forms of `/display` from the previous release, mapped to the command that replaced them. They don't run: the sender
 * is told the new form instead. Every rule only matches input that is not valid in the current grammar.
 * Remove after one release.
 */
object MovedHints {
    private val QUEUE = setOf("list", "add", "remove", "move", "skip", "clear", "loop")
    private val KARAOKE = setOf("status", "stop", "trim", "singer", "join", "leave", "line")
    private val SCHEDULE = setOf("show", "play", "pause", "cancel")
    private val PREPARE = setOf("start", "list", "cancel")
    private val DIAG = setOf("clients", "env", "loglevel", "errors")

    /** The current form of [words] (the input after `/display`, split on spaces), or null when it isn't an old form. */
    fun hint(words: List<String>): String? {
        val w = words.filter { it.isNotEmpty() }
        if (w.isEmpty()) return null
        val first = w[0].lowercase()
        val new: List<String>? = when (first) {
            "queue" -> targetFirst(w, QUEUE) { target, action, rest ->
                when (action) {
                    null -> listOf("queue", "list", target)
                    else -> listOf("queue", action, target) + rest
                }
            }

            "karaoke" -> targetFirst(w, KARAOKE + "on" + "off") { target, action, rest ->
                when (action) {
                    null -> listOf("karaoke", "status", target)
                    "off" -> listOf("karaoke", "stop", target)
                    "on" -> listOf("karaoke", "singer", "add", target, "<player>")
                    "singer", "line" -> {
                        val sub = rest.firstOrNull()?.lowercase()
                        if (action == "line" && sub == null) listOf("karaoke", "line", "list", target)
                        else listOf("karaoke", action) + listOfNotNull(sub) + target + rest.drop(1)
                    }

                    else -> listOf("karaoke", action, target) + rest
                }
            }

            "schedule" -> targetFirst(w, SCHEDULE) { target, action, rest ->
                listOf("schedule", action ?: "show", target) + rest
            }

            "prepare" -> if (w.size >= 2 && w[1].lowercase() !in PREPARE) {
                listOf("prepare", "start", w[1]) + startFlags(w.drop(2))
            } else null

            "fullscreen" -> fullscreen(w)
            "clients" -> listOf("diag", "clients")
            "errors" -> listOf("diag", "errors") + w.drop(1)
            "debug" -> listOf("diag", "loglevel") + w.drop(1)
            "diag" -> if (w.size >= 2 && w[1].lowercase() !in DIAG) listOf("diag", "env") + w.drop(1) else null
            "list" -> if (w.size >= 2 && w[1].equals("sync", ignoreCase = true)) {
                listOf("list", "mode", "synced") + w.drop(2)
            } else null

            else -> null
        }
        return new?.let { "/display " + it.joinToString(" ") }
    }

    /** `<area> <target> [action] [rest]` from the old grammar, when the second word is not one of [actions]. */
    private fun targetFirst(
        w: List<String>,
        actions: Set<String>,
        build: (target: String, action: String?, rest: List<String>) -> List<String>,
    ): List<String>? {
        if (w.size < 2 || w[1].lowercase() in actions) return null
        val action = w.getOrNull(2)?.lowercase()
        if (action != null && action !in actions) return null
        return build(w[1], action, w.drop(3))
    }

    private fun fullscreen(w: List<String>): List<String>? {
        val verb = w.getOrNull(1)?.lowercase() ?: return null
        val kind = w.getOrNull(2)?.lowercase()
        return when {
            verb == "start" && (kind == "id" || kind == "url") && w.size >= 4 ->
                listOf("fullscreen", "start", w[3]) + startFlags(w.drop(4))

            verb == "prepare" && (kind == "id" || kind == "url") && w.size >= 4 ->
                listOf("prepare", "start", w[3]) + startFlags(w.drop(4))

            verb == "prepare" -> listOf("prepare", "start", "<display|url>")
            else -> null
        }
    }

    /** Old flag spellings: `mode` became `view`, `looped` became `loop`. */
    private fun startFlags(flags: List<String>): List<String> = flags.map {
        when (it.lowercase()) {
            "mode" -> "view"
            "looped" -> "loop"
            else -> it
        }
    }
}
