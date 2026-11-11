package com.dreamdisplays.core.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandSpecTest {
    private val all = DisplayCommands.ALL

    @Test
    fun everyCommandHasALangDescriptionAndUniqueId() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val missing = all.map { it.key }.filter { it !in Lang.en }
        assertTrue(missing.isEmpty(), "missing lang keys: $missing")
        val areas = Area.entries.map { it.key }.filter { it !in Lang.en }
        assertTrue(areas.isEmpty(), "missing area keys: $areas")
        // Literals between an area and its commands (karaoke singer, karaoke line) carry a tooltip too
        val intermediates = all.flatMap { def -> (2 until def.path.size).map { "cmd." + def.path.take(it).joinToString(".") } }
            .distinct().filter { it !in Lang.en }
        assertTrue(intermediates.isEmpty(), "missing intermediate keys: $intermediates")
    }

    @Test
    fun everyMessageKeyTheTreeUsesExists() {
        val used = listOf(
            "cmd.help.header", "cmd.help.areaLine", "cmd.help.areaHeader", "cmd.help.commands", "cmd.help.line",
            "cmd.help.target", "cmd.error.moved", "cmd.error.usage", "cmd.error.unknown", "cmd.error.unknownTopic",
            "cmd.error.permission", "cmd.error.playersOnly", "cmd.error.noDisplay", "cmd.error.ambiguous",
            "cmd.error.notLooking", "cmd.error.needsPlayer", "cmd.error.flagUnknown", "cmd.error.flagTwice",
            "cmd.error.flagMissing", "cmd.error.flagNumber", "cmd.error.flagValue",
            // Handlers and platform suggestions
            "cmd.karaoke.usage", "cmd.queue.badPosition", "cmd.name.cleared", "cmd.video.cleared", "cmd.share.none",
            "cmd.share.stopped", "cmd.share.listNone", "cmd.share.listHeader", "cmd.share.listLine",
            "cmd.suggest.allDisplays", "cmd.suggest.clearVideo", "cmd.suggest.clearName",
        ) + (Flags.START + Flags.PREPARE).flatMap { f ->
            listOf(f.tooltip) + ((f.value as? Flag.Value.Choice)?.choices?.values ?: emptyList())
        }
        val missing = used.filter { it !in Lang.en }
        assertTrue(missing.isEmpty(), "missing: $missing")
    }

    @Test
    fun areaCommandsStartWithTheirAreaAndDisplaysComeRightAfterTheAction() {
        all.filter { it.area?.prefix == true }.forEach { assertEquals(it.area!!.id, it.path[0], it.id) }
        all.filter { it.area?.prefix == false }.forEach { def ->
            assertTrue(Area.entries.none { it.prefix && it.id == def.path[0] }, "${def.id} is under a prefix area word")
        }
        all.forEach { def ->
            val i = def.args.indexOfFirst { it.kind in setOf(Kind.DISPLAY, Kind.DISPLAY_OR_ALL, Kind.SOURCE) }
            assertTrue(i <= 0, "${def.id}: the display must be the first argument")
        }
    }

    @Test
    fun everyCommandHasACommandPermissionAndYmlDeclaresEveryNode() {
        all.filter { it.id != "help" }.forEach { def ->
            assertTrue(def.permission != null && def.permission in Perm.ALL, "${def.id}: ${def.permission}")
        }
        val declared = Regex("""^ {2}(dreamdisplays\.[a-z.]+):""", RegexOption.MULTILINE)
            .findAll(Lang.pluginYml).map { it.groupValues[1] }.toList()
        assertEquals(declared.size, declared.toSet().size, "a node is declared twice")
        assertEquals((listOf(Perm.ADMIN) + Perm.ALL + Perm.LEGACY.keys).toSet(), declared.toSet())
        // Every node is operator-only by default
        val defaults = Regex("""^ {4}default: (\S+)""", RegexOption.MULTILINE).findAll(Lang.pluginYml).map { it.groupValues[1] }.toList()
        assertEquals(declared.size, defaults.size)
        assertTrue(defaults.all { it == "op" }, defaults.toString())
        // admin grants every node; each legacy node grants its replacements
        val adminChildren = Lang.pluginYml.substringAfter("  dreamdisplays.admin:").substringBefore("\n  dreamdisplays.")
        Perm.ALL.forEach { assertTrue("      $it: true" in adminChildren, "admin lacks $it") }
        Perm.LEGACY.forEach { (old, new) ->
            val block = Lang.pluginYml.substringAfter("\n  $old:\n").substringBefore("\n  dreamdisplays.")
            new.forEach { assertTrue("      $it: true" in block, "$old lacks child $it") }
        }
    }

    @Test
    fun commandPermissionsPerNode() {
        val expected = mapOf(
            "create" to Perm.DISPLAY_CREATE, "delete" to Perm.DISPLAY_DELETE, "video" to Perm.PLAYBACK_VIDEO,
            "video.clear" to Perm.PLAYBACK_VIDEO, "stats" to Perm.DIAG, "queue.add" to Perm.QUEUE,
            "karaoke.join" to Perm.KARAOKE_JOIN, "karaoke.line.list" to Perm.KARAOKE_JOIN, "karaoke.leave" to Perm.KARAOKE_JOIN,
            "karaoke.line.add" to Perm.KARAOKE_MANAGE, "karaoke.stop" to Perm.KARAOKE_MANAGE, "prepare.start" to Perm.PREPARE,
            "fullscreen.stop" to Perm.FULLSCREEN, "share.stop" to Perm.SHARE_MANAGE, "config" to Perm.ADMIN_CONFIG,
            "reload" to Perm.ADMIN_CONFIG, "policy" to Perm.ADMIN_POLICY, "diag.errors" to Perm.DIAG,
            "on" to Perm.VIEWING_TOGGLE, "off" to Perm.VIEWING_TOGGLE,
        )
        expected.forEach { (id, perm) -> assertEquals(perm, DisplayCommands.byId(id).permission, id) }
    }

    @Test
    fun clientReferencedFormsStayValid() {
        // The client mod's settings tooltip tells players to run `/display off`
        val off = DisplayCommands.byId("off")
        assertEquals(listOf("off"), off.path)
        assertTrue(off.args.all { it.optional })
    }
}
