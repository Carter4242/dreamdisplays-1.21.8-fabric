package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.ClientUpdateCheck.Verdict.CURRENT
import com.dreamdisplays.core.protocol.common.ClientUpdateCheck.Verdict.OLDER
import com.dreamdisplays.core.protocol.common.ClientUpdateCheck.Verdict.URGENT
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientUpdateCheckTest {
    // a1 <- a2 <- a3 (urgent cut-off) <- a4 <- m1 (merge of a4 and b2) <- 11111111 (current release) <- 22222222
    // b2 (branch off a1, merged by m1); b1 (branch off a2, never merged)
    private val history = CommitHistory.parse(
        """
        # newest first
        22222222 11111111
        11111111 eeeeeee1
        eeeeeee1 aaaaaaa4 bbbbbbb2
        aaaaaaa4 aaaaaaa3
        bbbbbbb2 aaaaaaa1
        aaaaaaa3 aaaaaaa2
        aaaaaaa2 aaaaaaa1
        aaaaaaa1
        """.trimIndent()
    )
    private val latest = "11111111"

    private fun rules(
        latestVersion: String? = "1.10.9",
        latestBuild: String = latest,
        urgentBuilds: List<String> = emptyList(),
        urgentBefore: String = "aaaaaaa3",
        urgentUnknown: Boolean = true,
        history: CommitHistory = this.history,
    ) = ClientUpdateCheck.Rules(ModVersion.parse(latestVersion), latestBuild, history, urgentBuilds, urgentBefore, urgentUnknown)

    private fun check(build: String, version: String = "1.10.9", rules: ClientUpdateCheck.Rules = rules()) =
        ClientUpdateCheck.classify(version, build, rules)

    @Test
    fun currentReleaseAndItsDevBuildsGetNothing() {
        assertEquals(CURRENT, check(latest))
        assertEquals(CURRENT, check("$latest-dev"))
        assertEquals(CURRENT, check("$latest-dev-dirty"))
        assertEquals(CURRENT, check(latest.uppercase()))
    }

    @Test
    fun ancestorsOfTheReleaseAreOlder() {
        assertEquals(OLDER, check("aaaaaaa4"))
        assertEquals(OLDER, check("aaaaaaa4-dev-dirty"))
        assertEquals(OLDER, check("eeeeeee1"))
        // A branch commit merged into the release's history
        assertEquals(OLDER, check("bbbbbbb2"))
    }

    @Test
    fun newerAndUnknownCommitsGetNothing() {
        assertEquals(CURRENT, check("22222222"))
        assertEquals(CURRENT, check("22222222-dev"))
        assertEquals(CURRENT, check("cafebabe"))
        assertEquals(CURRENT, check("cafebabe-dev-dirty"))
        // A branch dev build off an old commit, never merged
        assertEquals(CURRENT, check("bbbbbbb1-dev"))
        // A build that knew build ids but couldn't read its commit
        assertEquals(CURRENT, check("unknown"))
    }

    @Test
    fun commitsOlderThanTheCutOffAreUrgent() {
        assertEquals(URGENT, check("aaaaaaa2"))
        assertEquals(URGENT, check("aaaaaaa1-dev"))
        // The cut-off itself is only older
        assertEquals(OLDER, check("aaaaaaa3"))
        assertEquals(OLDER, check("aaaaaaa2", rules = rules(urgentBefore = "")))
    }

    @Test
    fun listedBuildsAreUrgentWhateverTheirPlace() {
        val r = rules(urgentBuilds = listOf("aaaaaaa4", "bbbbbbb1", "1234567"))
        assertEquals(URGENT, check("aaaaaaa4", rules = r))
        assertEquals(URGENT, check("aaaaaaa4-dev-dirty", rules = r))
        assertEquals(URGENT, check("bbbbbbb1-dev", rules = r))
        assertEquals(URGENT, check("12345678", rules = r))
        assertEquals(URGENT, check("bbbbbbb1", "9.0.0", r))
        assertEquals(CURRENT, check(latest, rules = r))
    }

    @Test
    fun jarsWithoutABuildIdAreOlder() {
        assertEquals(URGENT, check(""))
        assertEquals(OLDER, check("", rules = rules(urgentUnknown = false)))
        // Even when the release's own build is unknown
        assertEquals(URGENT, check("", rules = rules(latestBuild = "")))
        assertEquals(OLDER, check("", rules = rules(latestBuild = "", urgentUnknown = false)))
    }

    @Test
    fun aDifferentModVersionDecidesBeforeCommits() {
        val v2 = rules(latestVersion = "2.0.0", latestBuild = "33333333")
        // Every 1.10.9 build is older than 2.0.0, whatever its commit
        assertEquals(OLDER, check(latest, "1.10.9", v2))
        assertEquals(OLDER, check("cafebabe-dev", "1.10.9", v2))
        assertEquals(URGENT, check("aaaaaaa2", "1.10.9", v2))
        assertEquals(URGENT, check("", "1.10.9", v2))
        assertEquals(OLDER, check("33333333", "2.0.0-beta.1", v2))
        // Newer versions get nothing, with or without a build id
        assertEquals(CURRENT, check("cafebabe", "2.0.1", v2))
        assertEquals(CURRENT, check("", "2.1.0", v2))
        assertEquals(CURRENT, check("aaaaaaa2", "3.0.0", v2))
        // Same version: by commit
        assertEquals(CURRENT, check("33333333", "2.0.0", v2))
        assertEquals(OLDER, check("aaaaaaa4", "2.0.0", rules(latestVersion = "2.0.0")))
        assertEquals(CURRENT, check("22222222", "2.0.0", rules(latestVersion = "2.0.0")))
    }

    @Test
    fun withoutHistoryOrReleaseBuildOnlyVersionsAndMissingIdsCount() {
        val bare = rules(history = CommitHistory.EMPTY)
        assertEquals(CURRENT, check("aaaaaaa1", rules = bare))
        assertEquals(URGENT, check("", rules = bare))
        assertEquals(OLDER, check("aaaaaaa1", "1.9.0", bare))
        assertEquals(CURRENT, check("aaaaaaa4", rules = rules(latestBuild = "")))
        assertEquals(CURRENT, check("aaaaaaa4", rules = rules(latestBuild = "deadbeef")))
        // An unparseable version falls back to the commit
        assertEquals(OLDER, check("aaaaaaa4", "weird"))
        assertEquals(OLDER, check("aaaaaaa4", rules = rules(latestVersion = null)))
    }

    @Test
    fun aShortReleaseIdResolvesWhenUnique() {
        assertEquals(OLDER, check("aaaaaaa4", rules = rules(latestBuild = "1111111")))
        assertEquals("11111111", history.resolve("1111111"))
        assertNull(history.resolve("aaaaaaa"))
        assertTrue(history.isStrictAncestor("aaaaaaa1", "11111111"))
        assertFalse(history.isStrictAncestor("11111111", "11111111"))
        assertFalse(history.isStrictAncestor("bbbbbbb1", "11111111"))
    }

    @Test
    fun modVersionsOrderSemantically() {
        fun v(s: String) = assertNotNull(ModVersion.parse(s), s)
        assertTrue(v("1.10.9") < v("2.0.0"))
        assertTrue(v("1.9.9") < v("1.10.0"))
        assertTrue(v("2.0.0-beta.1") < v("2.0.0-beta.2"))
        assertTrue(v("2.0.0-beta.2") < v("2.0.0-beta.10"))
        assertTrue(v("2.0.0-alpha") < v("2.0.0-beta"))
        assertTrue(v("2.0.0-beta.2") < v("2.0.0"))
        assertTrue(v("2.0.0") < v("2.0.1"))
        assertEquals(0, v("2.0").compareTo(v("2.0.0")))
        assertEquals(0, v("v2.0.0+abc").compareTo(v("2.0.0")))
        assertNull(ModVersion.parse(""))
        assertNull(ModVersion.parse("unknown"))
        assertNull(ModVersion.parse("2"))
    }

    @Test
    fun aNoticeGoesOutOncePerLogin() {
        val sessions = UpdateNoticeSessions()
        val alex = UUID.randomUUID()
        val token = assertNotNull(sessions.begin(alex))
        assertTrue(sessions.isCurrent(alex, token))
        // Another hello in the same login (e.g. after a re-greeting)
        assertNull(sessions.begin(alex))
        sessions.onQuit(alex)
        assertFalse(sessions.isCurrent(alex, token))
        val next = assertNotNull(sessions.begin(alex))
        assertTrue(sessions.isCurrent(alex, next))
        assertFalse(sessions.isCurrent(alex, token))
    }

    @Test
    fun playersOnlineWhenThePluginStartedAreSkippedUntilTheyRelog() {
        val sessions = UpdateNoticeSessions()
        val steve = UUID.randomUUID()
        val newcomer = UUID.randomUUID()
        sessions.pluginStarted(listOf(steve))
        assertNull(sessions.begin(steve))
        assertNotNull(sessions.begin(newcomer))
        sessions.onQuit(steve)
        assertNotNull(sessions.begin(steve))
    }
}
