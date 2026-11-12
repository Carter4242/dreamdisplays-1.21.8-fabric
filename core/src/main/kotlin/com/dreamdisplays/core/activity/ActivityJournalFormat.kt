package com.dreamdisplays.core.activity

import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * The permanent Activity journal's format: one JSON object per line in a daily file `activity-YYYY-MM-DD.log`
 * (server time zone), for every Activity row (automatic ones included) and every refused attempt. Pure, so it is
 * tested without a server.
 */
object ActivityJournalFormat {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val FILE = Regex("""activity-(\d{4}-\d{2}-\d{2})\.log""")

    /** Where a display stands: world and block coordinates (null for a virtual display). */
    data class Place(val world: String, val x: Int, val y: Int, val z: Int)

    /**
     * The line for [row] on display [displayId] ([displayName], at [place]). [old] is the value the action replaced
     * (previous URL, mode, access, name, speed), [refused] the reason an attempt was turned down (the row's kind is
     * then what was tried), [repeats] how many identical refused attempts were folded into this one.
     */
    fun line(
        row: ActivityEntryWire, displayId: UUID, displayName: String?, place: Place?, old: String = "",
        refused: String? = null, repeats: Int = 0, zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildJsonObject {
        put("time", JsonPrimitive(TIME.format(Instant.ofEpochMilli(row.atMs).atZone(zone))))
        put("ms", JsonPrimitive(row.atMs))
        put("event", JsonPrimitive(eventName(row.kind)))
        if (refused != null) {
            put("refused", JsonPrimitive(true))
            put("reason", JsonPrimitive(refused))
        }
        if (repeats > 0) put("repeats", JsonPrimitive(repeats))
        put("display", JsonPrimitive(displayId.toString()))
        displayName?.let { put("displayName", JsonPrimitive(it)) }
        place?.let {
            put("world", JsonPrimitive(it.world))
            put("x", JsonPrimitive(it.x))
            put("y", JsonPrimitive(it.y))
            put("z", JsonPrimitive(it.z))
        }
        when {
            row.console -> put("by", JsonPrimitive("console"))
            row.actorId != ZERO_UUID -> {
                put("by", JsonPrimitive("player"))
                put("player", JsonPrimitive(row.actorName))
                put("uuid", JsonPrimitive(row.actorId.toString()))
            }
            else -> put("by", JsonPrimitive("automatic"))
        }
        if (row.title.isNotEmpty()) put("title", JsonPrimitive(row.title))
        if (row.url.isNotEmpty()) put("url", JsonPrimitive(row.url))
        if (old.isNotEmpty()) put("old", JsonPrimitive(old))
        if (row.fromMs != 0L) put("fromMs", JsonPrimitive(row.fromMs))
        if (row.toMs != 0L) put("toMs", JsonPrimitive(row.toMs))
        if (row.rate != 0.0) put("rate", JsonPrimitive(row.rate))
        if (row.count != 0) put("count", JsonPrimitive(row.count))
        if (row.flag) put("flag", JsonPrimitive(true))
        if (row.text.isNotEmpty()) put("text", JsonPrimitive(row.text))
        if (row.text2.isNotEmpty()) put("text2", JsonPrimitive(row.text2))
    }.toString()

    /** The `event` name of a row kind (lower case; `kind_<n>` for one this build does not know). */
    fun eventName(kind: Int): String = ActivityKind.fromWire(kind)?.name?.lowercase(Locale.ROOT) ?: "kind_$kind"

    /** The day file for [date]. */
    fun fileName(date: LocalDate): String = "activity-$date.log"

    /** The day a journal file holds, or null for any other file. */
    fun dateOf(fileName: String): LocalDate? =
        FILE.matchEntire(fileName)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** True when the file named [fileName] is past [keepDays] on [today] (0 keeps everything). */
    fun expired(fileName: String, today: LocalDate, keepDays: Int): Boolean {
        if (keepDays <= 0) return false
        val date = dateOf(fileName) ?: return false
        return date.isBefore(today.minusDays(keepDays.toLong() - 1))
    }

    /** A journal line read back, or null when it is not one. */
    fun parse(line: String): JsonObject? = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()

    /** Time (epoch ms) of a parsed line, or null. */
    fun msOf(obj: JsonObject): Long? = obj["ms"]?.jsonPrimitive?.longOrNull

    /** True when the parsed line is about display [displayId] or by player [playerId] / [playerName]. */
    fun matches(obj: JsonObject, displayId: UUID?, playerId: UUID?, playerName: String?): Boolean {
        if (displayId != null && obj["display"]?.jsonPrimitive?.content == displayId.toString()) return true
        if (playerId != null && obj["uuid"]?.jsonPrimitive?.content == playerId.toString()) return true
        return playerName != null && obj["player"]?.jsonPrimitive?.content.equals(playerName, ignoreCase = true)
    }
}
