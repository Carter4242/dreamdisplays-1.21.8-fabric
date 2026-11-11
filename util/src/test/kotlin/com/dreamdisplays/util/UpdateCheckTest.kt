package com.dreamdisplays.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    @Test
    fun `the fork's release tags name the mod version`() {
        assertEquals("1.10.9", UpdateCheck.releaseVersion("v1.10.9-mc1.21.8"))
        assertEquals("1.10.10", UpdateCheck.releaseVersion("v1.10.10-mc1.21.8"))
        assertEquals("1.11", UpdateCheck.releaseVersion("1.11"))
        assertNull(UpdateCheck.releaseVersion("ffmpeg-builds"))
        assertNull(UpdateCheck.releaseVersion("mc1.21.8"))
    }

    @Test
    fun `a newer fork release shows the arrow and the same one does not`() {
        assertTrue(UpdateCheck.compareVersions(UpdateCheck.releaseVersion("v1.10.10-mc1.21.8")!!, "1.10.9") > 0)
        assertEquals(0, UpdateCheck.compareVersions(UpdateCheck.releaseVersion("v1.10.9-mc1.21.8")!!, "1.10.9"))
    }

    @Test
    fun `the check and the arrow point at the fork, not upstream`() {
        assertEquals("https://github.com/Carter4242/dreamdisplays-1.21.8-fabric/releases/latest", UpdateCheck.RELEASES_PAGE)
    }
}
