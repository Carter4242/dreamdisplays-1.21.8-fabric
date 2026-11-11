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
        // The reason for the rule: names win over id prefixes
        val displays = listOf(TargetMatch.Candidate(victim, null), TargetMatch.Candidate(decoy, "a1b2c3d4"))
        assertEquals(TargetMatch.Result.Found(decoy), TargetMatch.match("a1b2c3d4", displays))
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
