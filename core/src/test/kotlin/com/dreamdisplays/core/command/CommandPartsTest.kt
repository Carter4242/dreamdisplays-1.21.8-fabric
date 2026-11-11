package com.dreamdisplays.core.command

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetMatchTest {
    private val lobby = TargetMatch.Candidate(UUID.fromString("619a9ce2-f01f-40ac-9101-af5b00000001"), "lobby")
    private val a = TargetMatch.Candidate(UUID.fromString("61f0aaaa-0000-0000-0000-000000000002"), null)
    private val b = TargetMatch.Candidate(UUID.fromString("61f0bbbb-0000-0000-0000-000000000003"), "stage")
    private val all = listOf(lobby, a, b)

    @Test
    fun thisAndLeftOffMeanLookedAt() {
        assertEquals(TargetMatch.Result.LookedAt, TargetMatch.match(null, all))
        assertEquals(TargetMatch.Result.LookedAt, TargetMatch.match("THIS", all))
    }

    @Test
    fun idsNamesAndPrefixes() {
        assertEquals(TargetMatch.Result.Found(lobby.id), TargetMatch.match(lobby.id.toString(), all))
        assertEquals(TargetMatch.Result.Found(lobby.id), TargetMatch.match("LOBBY", all))
        assertEquals(TargetMatch.Result.Found(lobby.id), TargetMatch.match("619a", all))
        assertEquals(TargetMatch.Result.Found(b.id), TargetMatch.match("61f0b", all))
        assertEquals(TargetMatch.Result.NotFound, TargetMatch.match("61", all))
        assertEquals(TargetMatch.Result.NotFound, TargetMatch.match("nope", all))
        assertEquals(TargetMatch.Result.NotFound, TargetMatch.match(UUID.randomUUID().toString(), all))
    }

    @Test
    fun ambiguousPrefixesListTheCandidates() {
        assertEquals(TargetMatch.Result.Ambiguous(listOf("61f0aaaa", "stage")), TargetMatch.match("61f0", all))
    }

    @Test
    fun aNameWinsOverAPrefix() {
        val named = TargetMatch.Candidate(UUID.randomUUID(), "61f0")
        assertEquals(TargetMatch.Result.Found(named.id), TargetMatch.match("61f0", all + named))
    }
}

class MovedHintsTest {
    private fun hint(s: String) = MovedHints.hint(s.split(' '))

    @Test
    fun oldFormsMapToTheirNewCommand() {
        assertEquals("/display queue list test", hint("queue test"))
        assertEquals("/display queue add test https://x", hint("queue test add https://x"))
        assertEquals("/display queue loop test on", hint("queue test loop on"))
        assertEquals("/display karaoke status test", hint("karaoke test"))
        assertEquals("/display karaoke stop test", hint("karaoke test off"))
        assertEquals("/display karaoke singer add test <player>", hint("karaoke test on"))
        assertEquals("/display karaoke singer add test Bubs duet", hint("karaoke test singer add Bubs duet"))
        assertEquals("/display karaoke line list test", hint("karaoke test line"))
        assertEquals("/display karaoke line add test Bubs https://x", hint("karaoke test line add Bubs https://x"))
        assertEquals("/display schedule show test", hint("schedule test"))
        assertEquals("/display schedule play test 18:00", hint("schedule test play 18:00"))
        assertEquals("/display fullscreen start test view immersive loop", hint("fullscreen start id test mode immersive looped"))
        assertEquals("/display prepare start https://x rewind", hint("fullscreen prepare url https://x rewind"))
        assertEquals("/display prepare start test", hint("prepare test"))
        assertEquals("/display diag clients", hint("clients"))
        assertEquals("/display diag errors Bubs hitches", hint("errors Bubs hitches"))
        assertEquals("/display diag env Bubs", hint("diag Bubs"))
        assertEquals("/display diag loglevel Bubs ultra", hint("debug Bubs ultra"))
        assertEquals("/display list mode synced 2", hint("list sync 2"))
    }

    @Test
    fun currentFormsAreNotHinted() {
        listOf(
            "queue", "queue add test https://x", "queue list", "karaoke stop test", "karaoke singer add test Bubs",
            "schedule play test 18:00", "prepare start test", "prepare list", "fullscreen start test loop",
            "fullscreen list", "diag env Bubs", "diag errors", "list mode synced", "list 2", "stats test", "help",
        ).forEach { assertNull(hint(it), it) }
    }
}

class FlagsTest {
    private val any = { _: String -> true }

    @Test
    fun parsesSwitchesWordsNumbersChoicesAndRadius() {
        val r = Flags.parse(Flags.START, "radius 8 transient quality 480 view standard", any) as Flags.Result.Ok
        assertEquals(listOf("8"), r.flags.values["radius"])
        assertTrue(r.flags.has("transient"))
        assertEquals("480", r.flags.word("quality"))
        assertEquals("standard", r.flags.word("view"))
        assertTrue(Flags.parse(Flags.START, null, any) is Flags.Result.Ok)
    }

    @Test
    fun errors() {
        fun err(text: String) = (Flags.parse(Flags.START, text, any) as Flags.Result.Error).key
        assertEquals("cmd.error.flagMissing", err("volume"))
        assertEquals("cmd.error.flagNumber", err("volume loud"))
        assertEquals("cmd.error.flagValue", err("view sideways"))
        assertEquals("cmd.error.flagUnknown", err("rewind"))
        assertEquals("cmd.error.flagTwice", err("forced forced"))
    }

    @Test
    fun slotOfTheWordBeingTyped() {
        assertTrue(Flags.slot(Flags.START, "", any) is Flags.Slot.Name)
        val v = Flags.slot(Flags.START, "volume 1", any) as Flags.Slot.Value
        assertEquals("volume", v.flag.name)
        val r = Flags.slot(Flags.START, "radius 8 1 ", any) as Flags.Slot.Value
        assertEquals(2, r.index)
        val after = Flags.slot(Flags.START, "radius 8 1 2 3 lo", any) as Flags.Slot.Name
        assertEquals(setOf("radius"), after.used)
        assertNull(Flags.slot(Flags.START, "bogus ", any))
    }
}
