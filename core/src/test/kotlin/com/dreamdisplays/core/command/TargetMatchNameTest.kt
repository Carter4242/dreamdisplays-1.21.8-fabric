package com.dreamdisplays.core.command

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetMatchNameTest {
    private val victim = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")
    private val decoy = UUID.fromString("ffff0000-0000-4000-8000-000000000002")

    @Test
    fun aNameEqualToAnotherDisplaysShortIdWouldTakeItsCommands() {
        // The reason for the rule: names win over id prefixes. Matching only catches a name equal to the listed 8-character
        // short id (ambiguous); any other prefix of the id would still reach the named display
        val listed = listOf(TargetMatch.Candidate(victim, null), TargetMatch.Candidate(decoy, "a1b2c3d4"))
        assertTrue(TargetMatch.match("a1b2c3d4", listed) is TargetMatch.Result.Ambiguous)
        val shorter = listOf(TargetMatch.Candidate(victim, null), TargetMatch.Candidate(decoy, "a1b2"))
        assertEquals(TargetMatch.Result.Found(decoy), TargetMatch.match("a1b2", shorter))
        assertTrue(TargetMatch.nameShadowsId("a1b2c3d4", victim))
        assertTrue(TargetMatch.nameShadowsId("A1B2", victim))
        assertTrue(TargetMatch.nameShadowsId("a1b2c3d4-0000", victim))
    }

    @Test
    fun namesTooShortForAPrefixOrNotAPrefixAreFine() {
        assertFalse(TargetMatch.nameShadowsId("a1b", victim))
        assertFalse(TargetMatch.nameShadowsId("cafe", victim))
        assertFalse(TargetMatch.nameShadowsId("lobby", victim))
    }
}
