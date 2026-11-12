package com.dreamdisplays.core.activity

import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTextTest {
    private val langDir = File("../platform/resources/src/main/resources/assets/dreamdisplays/lang/client")
    private val en: JsonObject = Json.parseToJsonElement(File(langDir, "en_us.json").readText()) as JsonObject

    /** [e] as the English menu shows it. */
    private fun english(e: ActivityEntryWire, titleOf: (String) -> String? = { null }): String {
        val line = ActivityText.describe(e, titleOf) ?: return "<none>"
        val args = line.args.mapIndexed { i, a -> line.argKeys.getOrNull(i)?.let { en[it]!!.jsonPrimitive.content } ?: a }
        return String.format(Locale.ROOT, en[line.key]!!.jsonPrimitive.content, *args.toTypedArray())
    }

    private fun row(kind: ActivityKind) = ActivityEntryWire(kind = kind.wire)

    @Test
    fun `rows read plainly`() {
        assertEquals("played", english(row(ActivityKind.PLAY)))
        assertEquals("seeked from 3:26 to 1:02:10", english(row(ActivityKind.SEEK).copy(fromMs = 206_000, toMs = 3_730_000)))
        assertEquals("changed speed to 1.5×", english(row(ActivityKind.SPEED).copy(rate = 1.5)))
        assertEquals("changed speed to 2×", english(row(ActivityKind.SPEED).copy(rate = 2.0)))
        assertEquals("set the video to \"Never Gonna\"", english(row(ActivityKind.VIDEO_SET).copy(title = "Never Gonna", url = "https://x/y")))
        assertEquals(
            "set the video to \"example.com/videos/a.mp4\"",
            english(row(ActivityKind.VIDEO_SET).copy(url = "https://www.example.com/videos/a.mp4")),
        )
        assertEquals(
            "added \"Cached\" to Up next",
            english(row(ActivityKind.QUEUE_ADD).copy(url = "https://youtu.be/abc")) { if (it.endsWith("abc")) "Cached" else null },
        )
        assertEquals("added a playlist (12 videos)", english(row(ActivityKind.QUEUE_ADD_PLAYLIST).copy(count = 12)))
        assertEquals("moved \"A\" up", english(row(ActivityKind.QUEUE_MOVE).copy(title = "A", fromMs = 3, toMs = 2)))
        assertEquals("moved \"A\" down", english(row(ActivityKind.QUEUE_MOVE).copy(title = "A", fromMs = 2, toMs = 3)))
        assertEquals("moved \"A\" to position 1", english(row(ActivityKind.QUEUE_MOVE).copy(title = "A", fromMs = 5, toMs = 1)))
        assertEquals("turned loop on", english(row(ActivityKind.QUEUE_LOOP).copy(flag = true)))
        assertEquals("changed the mode to Broadcast", english(row(ActivityKind.MODE).copy(text = "broadcast")))
        assertEquals("changed access to weird", english(row(ActivityKind.ACCESS).copy(text = "weird")))
        assertEquals("Up next moved on to \"B\"", english(row(ActivityKind.QUEUE_NEXT).copy(title = "B")))
        assertNull(ActivityText.describe(ActivityEntryWire(kind = 9_999)))
    }

    @Test
    fun `every kind has a line`() {
        for (kind in ActivityKind.entries) {
            val line = ActivityText.describe(row(kind))!!
            assertTrue(line.key in ActivityText.allKeys, "${kind.name}: ${line.key}")
        }
    }

    @Test
    fun `positions speeds and times`() {
        assertEquals("0:00", ActivityText.position(0))
        assertEquals("0:59", ActivityText.position(59_400))
        assertEquals("10:00", ActivityText.position(600_000))
        assertEquals("1:00:00", ActivityText.position(3_600_000))
        assertEquals("0.75×", ActivityText.speed(0.75))
        assertEquals("1.25×", ActivityText.speed(1.25))
        assertEquals("ago.now", ActivityText.ago(3_000).key.substringAfter("activity."))
        assertEquals(ActivityText.Line("dreamdisplays.activity.ago.minutes", ["2"]), ActivityText.ago(150_000))
        assertEquals(ActivityText.Line("dreamdisplays.activity.ago.days", ["3"]), ActivityText.ago(3 * 86_400_000L + 5))
        assertEquals("youtube.com/watch?v=dQw4w9WgXcQ", ActivityText.shortUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(48, ActivityText.shortUrl("https://e.com/" + "a".repeat(100)).length)
        assertEquals("1970-01-01 00:00:01", ActivityText.exact(1_000, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `every language file has every Activity and Viewers key`() {
        val keys = ActivityText.allKeys + VIEWER_KEYS
        val files = langDir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(files.size >= 12)
        for (file in files) {
            val json = Json.parseToJsonElement(file.readText()) as JsonObject
            val missing = keys.filter { it !in json }
            assertTrue(missing.isEmpty(), "${file.name} lacks $missing")
            // Same placeholders as English, so a translation can't drop or add an argument
            for (key in keys) {
                val count = { s: String -> Regex("%(\\d+\\$)?s").findAll(s).count() }
                assertEquals(count(en[key]!!.jsonPrimitive.content), count(json[key]!!.jsonPrimitive.content), "${file.name} $key")
            }
        }
    }

    companion object {
        /** Menu strings of the two tabs, outside the rows themselves. */
        val VIEWER_KEYS = [
            "dreamdisplays.activity.tab", "dreamdisplays.activity.tab.short", "dreamdisplays.activity.empty",
            "dreamdisplays.activity.automatic", "dreamdisplays.activity.console", "dreamdisplays.viewers.tab",
            "dreamdisplays.viewers.tab.short", "dreamdisplays.viewers.empty", "dreamdisplays.viewers.via.nearby",
            "dreamdisplays.viewers.via.pip", "dreamdisplays.viewers.via.fullscreen", "dreamdisplays.viewers.status.loading",
            "dreamdisplays.viewers.status.buffering", "dreamdisplays.viewers.status.error",
            "dreamdisplays.viewers.status.no_mod", "dreamdisplays.viewers.you", "dreamdisplays.queue.tab.short",
            "dreamdisplays.button.suggestions.short",
        ]
    }
}
