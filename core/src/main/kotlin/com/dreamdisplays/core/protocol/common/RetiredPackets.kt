package com.dreamdisplays.core.protocol.common

/**
 * Packets removed from protocol v2 (the watch party, superseded by the shared timeline). Their type ids, message names
 * and field numbers stay reserved: [PacketType] refuses the ids, the committed `dreamdisplays.proto` lists the messages
 * with every field reserved, and a receiver drops them as unknown ids ([PacketRegistry.decode] returns null).
 */
object RetiredPackets {
    /** A removed packet: its `Envelope.type` [id], its schema [message] name and the [fields] it had, by number. */
    class Retired(val id: Int, val message: String, val fields: Map<Int, String>)

    val entries: List<Retired> = listOf(
        Retired(14, "WatchPartyStart", fieldsOf("id", "url", "lang")),
        Retired(15, "WatchPartyControl", fieldsOf("id", "action", "positionMs")),
        Retired(
            16, "WatchPartyState",
            fieldsOf(
                "id", "sessionId", "state", "hostId", "hostName", "url", "lang", "readyCount", "nearbyCount",
                "countdownStartEpochMs", "positionMs", "serverTimeMs", "durationMs", "paused",
            ),
        ),
    )

    /** Every retired `Envelope.type` id; never reuse one. */
    val ids: Set<Int> = entries.map { it.id }.toSet()

    /** The schema text reserving each retired message's field numbers and names (appended to the generated schema). */
    fun schemaText(): String = entries.joinToString("") { retired ->
        val numbers = retired.fields.keys.sorted()
        val names = numbers.joinToString(", ") { "\"${retired.fields.getValue(it)}\"" }
        "\n// Retired packet: Envelope.type ${retired.id}. Never reuse the type id, the message name or its fields.\n" +
                "message ${retired.message} {\n" +
                "  reserved ${numbers.first()} to ${numbers.last()};\n" +
                "  reserved $names;\n" +
                "}\n"
    }

    private fun fieldsOf(vararg names: String): Map<Int, String> =
        names.withIndex().associate { (index, name) -> index + 1 to name }
}
