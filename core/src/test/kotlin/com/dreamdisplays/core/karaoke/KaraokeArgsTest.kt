package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.karaoke.KaraokeAction.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KaraokeArgsTest {
    private fun parse(line: String) = KaraokeArgs.parse(line.split(' '))

    @Test
    fun toggleAndStatus() {
        assertEquals(KaraokeAction.Toggle(true), parse("on"))
        assertEquals(KaraokeAction.Toggle(false), parse("OFF"))
        assertEquals(KaraokeAction.Status, parse("status"))
        assertEquals(KaraokeAction.Status, KaraokeArgs.parse(emptyList()))
    }

    @Test
    fun trims() {
        assertEquals(KaraokeAction.Trim(-40), parse("trim -40"))
        assertEquals(KaraokeAction.Trim(25), parse("trim +25"))
        assertEquals(KaraokeAction.Invalid(Reason.BAD_TRIM), parse("trim 5000"))
        assertEquals(KaraokeAction.Invalid(Reason.BAD_TRIM), parse("trim soon"))
        assertEquals(KaraokeAction.SingerTrim("Alice", -15), parse("singer trim Alice -15"))
    }

    @Test
    fun singers() {
        assertEquals(KaraokeAction.SingerAdd("Alice", null), parse("singer add Alice"))
        assertEquals(KaraokeAction.SingerAdd("Alice", 400), parse("singer add Alice 400"))
        assertEquals(KaraokeAction.Invalid(Reason.BAD_LEAD), parse("singer add Alice -1"))
        assertEquals(KaraokeAction.Invalid(Reason.BAD_LEAD), parse("singer add Alice 9000"))
        assertEquals(KaraokeAction.SingerRemove("Bob"), parse("singer remove Bob"))
        assertEquals(KaraokeAction.Invalid(Reason.USAGE), parse("singer add"))
        assertEquals(KaraokeAction.Invalid(Reason.USAGE), parse("singer kick Bob"))
    }

    @Test
    fun theLine() {
        assertEquals(KaraokeAction.Join("https://youtu.be/abc?t=1"), parse("join https://youtu.be/abc?t=1"))
        assertEquals(KaraokeAction.Leave, parse("leave"))
        assertEquals(KaraokeAction.Line, parse("line"))
        assertEquals(KaraokeAction.Line, parse("line list"))
        assertEquals(KaraokeAction.LineAdd("Bob", "https://youtu.be/x"), parse("line add Bob https://youtu.be/x"))
        assertEquals(KaraokeAction.LineRemove("Bob"), parse("line remove Bob"))
        assertEquals(KaraokeAction.LineClear, parse("line clear"))
        assertEquals(KaraokeAction.Invalid(Reason.USAGE), parse("join"))
        assertEquals(KaraokeAction.Invalid(Reason.USAGE), parse("line add Bob"))
    }

    @Test
    fun onlyJoiningLeavingAndViewingTheLineAreForNonOperators() {
        assertFalse(parse("join https://x.y/z").operatorOnly)
        assertFalse(parse("leave").operatorOnly)
        assertFalse(parse("line").operatorOnly)
        assertTrue(parse("line add Bob https://x.y/z").operatorOnly)
        assertTrue(parse("line clear").operatorOnly)
        assertTrue(parse("on").operatorOnly)
        assertTrue(parse("status").operatorOnly)
        assertTrue(parse("singer add Bob").operatorOnly)
    }

    @Test
    fun garbage() {
        assertEquals(KaraokeAction.Invalid(Reason.USAGE), parse("sing"))
        assertEquals(KaraokeAction.Invalid(Reason.USAGE), parse("on now"))
    }
}
