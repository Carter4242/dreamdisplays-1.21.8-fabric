package com.dreamdisplays.platform.server.commands.suggest

/** [names] that contain [raw] (case-insensitive): exact match first, then prefixes, then the rest; at most [limit]. */
internal fun rank(names: List<String>, raw: String, limit: Int = Int.MAX_VALUE): List<String> {
    val needle = raw.lowercase()
    if (needle.isEmpty()) return names.take(limit)
    return names
        .mapNotNull { name ->
            val lower = name.lowercase()
            val score = when {
                lower == needle -> 0
                lower.startsWith(needle) -> 1
                lower.contains(needle) -> 2
                else -> return@mapNotNull null
            }
            score to name
        }
        .sortedBy { it.first }
        .take(limit)
        .map { it.second }
}
