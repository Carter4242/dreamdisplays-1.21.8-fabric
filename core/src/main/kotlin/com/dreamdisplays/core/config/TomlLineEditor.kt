package com.dreamdisplays.core.config

/**
 * Minimal in-place editor for a hand-written TOML config: sets or comments out one `key = value` line inside a
 * `[table]`, leaving every other line (comments, spacing, other keys) exactly as it was.
 *
 * Only plain `key = value` lines directly under a `[table]` header are understood (no dotted keys, inline
 * tables or multi-line values for the edited key), which is how the bundled config is written. Callers should
 * re-parse the result before saving it.
 */
object TomlLineEditor {
    private val HEADER = Regex("""^\s*\[\s*([A-Za-z0-9_.\-]+)\s*]\s*(#.*)?$""")

    /**
     * Returns [text] with [key] in [table] set to [tomlValue] (already TOML-encoded, see [encode]):
     * an active line is replaced in place, else a commented-out `# key = ...` example in that table is
     * uncommented and replaced, else the key is added at the end of the table; a missing table is appended.
     * A trailing comment on the replaced line is kept.
     */
    fun set(text: String, table: String, key: String, tomlValue: String): String {
        val lines = split(text)
        val range = tableRange(lines, table)
        if (range == null) {
            val out = lines.toMutableList()
            while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
            if (out.isNotEmpty()) out.add("")
            out.add("[$table]")
            out.add("$key = $tomlValue")
            return join(out, text, trailingNewline = true)
        }
        val out = lines.toMutableList()
        findKey(lines, range, key, commented = false)?.let { i ->
            out[i] = rewrite(lines[i], key, tomlValue, uncomment = false)
            return join(out, text)
        }
        findKey(lines, range, key, commented = true)?.let { i ->
            out[i] = rewrite(lines[i], key, tomlValue, uncomment = true)
            return join(out, text)
        }
        // Insert after the table's last key line, so comments introducing the next table stay with it;
        // a table with no keys yet gets it after its last non-blank (comment) line
        val lastKey = range.lastOrNull { it < lines.size && isKeyLine(lines[it]) }
        var at = lastKey ?: range.last
        if (lastKey == null) while (at > range.first && lines[at].isBlank()) at--
        out.add(at + 1, "$key = $tomlValue")
        return join(out, text)
    }

    /** Returns [text] with the active [key] line in [table] commented out (back to "unset"), or unchanged. */
    fun unset(text: String, table: String, key: String): String {
        val lines = split(text)
        val range = tableRange(lines, table) ?: return text
        val i = findKey(lines, range, key, commented = false) ?: return text
        val out = lines.toMutableList()
        val indent = lines[i].takeWhile { it == ' ' || it == '\t' }
        out[i] = indent + "# " + lines[i].trimStart()
        return join(out, text)
    }

    /** TOML text for a Kotlin value: booleans, whole numbers, decimals (always with a point) and strings. */
    fun encode(value: Any): String = when (value) {
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) "${value.toLong()}.0" else value.toString()
        is Float -> encode(value.toDouble())
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is List<*> -> value.joinToString(", ", "[", "]") { encode(it ?: throw IllegalArgumentException("null in $value")) }
        else -> throw IllegalArgumentException("Unsupported TOML value: $value")
    }

    private fun split(text: String): List<String> = text.replace("\r\n", "\n").split("\n")

    private fun join(lines: List<String>, original: String, trailingNewline: Boolean = false): String {
        val newline = if ("\r\n" in original) "\r\n" else "\n"
        var body = lines.joinToString("\n")
        if (trailingNewline && !body.endsWith("\n")) body += "\n"
        return if (newline == "\n") body else body.replace("\n", newline)
    }

    /** Line indices belonging to [table] (after its header, up to the next header), or null if absent. */
    private fun tableRange(lines: List<String>, table: String): IntRange? {
        val start = lines.indexOfFirst { HEADER.matchEntire(it)?.groupValues?.get(1) == table }
        if (start < 0) return null
        var end = lines.size - 1
        for (i in start + 1 until lines.size) {
            if (HEADER.matches(lines[i]) || lines[i].trimStart().startsWith("[[")) {
                end = i - 1
                break
            }
        }
        return (start + 1)..end.coerceAtLeast(start)
    }

    private fun isKeyLine(line: String): Boolean {
        val t = line.trimStart()
        return t.isNotEmpty() && !t.startsWith("#") && '=' in t
    }

    private fun findKey(lines: List<String>, range: IntRange, key: String, commented: Boolean): Int? {
        val pattern = if (commented) Regex("""^\s*#\s*${Regex.escape(key)}\s*=.*$""")
        else Regex("""^\s*${Regex.escape(key)}\s*=.*$""")
        return range.firstOrNull { it < lines.size && pattern.matches(lines[it]) }
    }

    /** Rebuilds a `key = value  # comment` line with a new value, keeping indentation and the comment. */
    private fun rewrite(line: String, key: String, tomlValue: String, uncomment: Boolean): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val body = if (uncomment) line.trimStart().removePrefix("#").trimStart() else line.trimStart()
        val afterEq = body.substringAfter('=')
        val comment = trailingComment(afterEq)
        return buildString {
            append(indent).append(key).append(" = ").append(tomlValue)
            if (comment != null) {
                // Keep the comment where it was when the new value is not longer than the old one
                val oldValue = afterEq.substring(0, afterEq.length - comment.length).trimEnd()
                val gap = (oldValue.trimStart().length - tomlValue.length + spacesBefore(afterEq, comment))
                    .coerceAtLeast(1)
                append(" ".repeat(gap)).append(comment)
            }
        }
    }

    private fun spacesBefore(afterEq: String, comment: String): Int {
        val beforeComment = afterEq.substring(0, afterEq.length - comment.length)
        return beforeComment.length - beforeComment.trimEnd().length
    }

    /** The `# ...` comment ending a value, or null. Skips `#` inside quoted strings. */
    private fun trailingComment(afterEq: String): String? {
        var inString = false
        var quote = ' '
        var i = 0
        while (i < afterEq.length) {
            val c = afterEq[i]
            when {
                inString && c == '\\' && quote == '"' -> i++
                inString && c == quote -> inString = false
                !inString && (c == '"' || c == '\'') -> { inString = true; quote = c }
                !inString && c == '#' -> return afterEq.substring(i)
            }
            i++
        }
        return null
    }
}
