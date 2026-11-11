package com.dreamdisplays.core.config

import com.dreamdisplays.core.display.policy.PolicyPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Edits the shipped config.toml template the way `/display config` does and checks where each key lands. */
class BundledConfigEditTest {
    private val template = File("../platform/resources/src/main/resources/assets/dreamdisplays/lang/server/config.toml")
        .readText()

    private fun lines(text: String) = text.replace("\r\n", "\n").split("\n")

    /** The lines of [table] only. */
    private fun tableLines(text: String, table: String): List<String> {
        var current: String? = null
        val out = ArrayList<String>()
        for (l in lines(text)) {
            Regex("""^\s*\[\s*([A-Za-z0-9_.\-]+)\s*]""").find(l)?.let { current = it.groupValues[1] }
            if (current == table) out.add(l)
        }
        return out
    }

    @Test
    fun `every policy key lands as one active line in its table`() {
        for (path in PolicyPaths.all) {
            val table = "policy." + path.name.substringBefore('.')
            val key = path.name.substringAfter('.')
            val out = TomlLineEditor.set(template, table, key, "7")
            val active = tableLines(out, table).filter { it.startsWith("$key = ") }
            assertEquals(1, active.size, "${path.name}: $active")
            assertTrue(active.single().startsWith("$key = 7"), active.single())
            // Nothing outside the table changed
            assertEquals(lines(template).size, lines(out).size, "${path.name} should uncomment its example")

            val unset = tableLines(TomlLineEditor.unset(out, table, key), table)
            assertTrue(unset.none { it.startsWith("$key = ") }, "${path.name} still active after unset")
            assertTrue(unset.any { it.startsWith("# $key = 7") }, "${path.name} not commented out")
        }
    }

    @Test
    fun `display keys are replaced in place under display`() {
        for (key in listOf("resume_hold_seconds", "seek_hold_seconds", "default_mode", "auto_pause_when_empty_seconds")) {
            val out = TomlLineEditor.set(template, "display", key, "5")
            val line = tableLines(out, "display").single { it.startsWith("$key = ") }
            assertEquals("$key = 5", line.substringBefore(" #").trimEnd(), key)
            assertEquals(lines(template).size, lines(out).size, "$key should be replaced, not added")
        }
    }
}
