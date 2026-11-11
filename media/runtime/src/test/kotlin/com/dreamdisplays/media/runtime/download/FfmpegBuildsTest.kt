package com.dreamdisplays.media.runtime.download

import com.dreamdisplays.media.runtime.download.FfmpegBuilds.Release
import com.dreamdisplays.media.runtime.download.FfmpegBuilds.Variant
import com.dreamdisplays.media.runtime.download.FfmpegBuilds.Want
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FfmpegBuildsTest {
    private val cli = Want("win64", "zip", Variant.GPL, major = 9, otherMajors = true, oldestMajor = 8)
    private val libs = Want("win64", "zip", Variant.LGPL_SHARED, major = 9)

    /** File names as FFmpeg-Builds' `latest` carried them on 2026-11-11 (one platform, all variants). */
    private val latestToday = listOf(
        "ffmpeg-master-latest-win64-gpl-shared.zip", "ffmpeg-master-latest-win64-gpl.zip",
        "ffmpeg-master-latest-win64-lgpl-shared.zip", "ffmpeg-master-latest-win64-lgpl.zip",
        "ffmpeg-master-latest-winarm64-gpl.zip",
        "ffmpeg-n8.1-latest-win64-gpl-8.1.zip", "ffmpeg-n8.1-latest-win64-gpl-shared-8.1.zip",
        "ffmpeg-n8.1-latest-win64-lgpl-8.1.zip", "ffmpeg-n8.1-latest-win64-lgpl-shared-8.1.zip",
        "ffmpeg-n9.0-latest-win64-gpl-9.0.zip", "ffmpeg-n9.0-latest-win64-gpl-shared-9.0.zip",
        "ffmpeg-n9.0-latest-win64-lgpl-9.0.zip", "ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip",
        "ffmpeg-n9.0-latest-winarm64-gpl-9.0.zip", "ffmpeg-n9.0-latest-linux64-gpl-9.0.tar.xz",
    )

    private fun sums(vararg names: String) = names.associateWith { "0".repeat(64) }

    @Test
    fun `today's latest gives the 9_0 builds`() {
        assertEquals("ffmpeg-n9.0-latest-win64-gpl-9.0.zip", FfmpegBuilds.pickWanted(latestToday, cli)?.name)
        assertEquals("ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip", FfmpegBuilds.pickWanted(latestToday, libs)?.name)
        val arm = cli.copy(arch = "winarm64")
        assertEquals("ffmpeg-n9.0-latest-winarm64-gpl-9.0.zip", FfmpegBuilds.pickWanted(latestToday, arm)?.name)
        assertEquals(
            "ffmpeg-n9.0-latest-linux64-gpl-9.0.tar.xz",
            FfmpegBuilds.pickWanted(latestToday, cli.copy(arch = "linux64", extension = "tar.xz"))?.name,
        )
    }

    @Test
    fun `dated release names parse to their branch`() {
        val b = FfmpegBuilds.parse("ffmpeg-n9.0.2-39-g7adfbe40dc-win64-lgpl-shared-9.0.zip", libs)!!
        assertEquals("9.0", b.branch)
        assertEquals("master", FfmpegBuilds.parse("ffmpeg-N-128227-g4811625b5f-win64-gpl.zip", cli)?.branch)
        assertEquals("6.1", FfmpegBuilds.parse("ffmpeg-n6.1.3-win64-gpl-6.1.zip", cli)?.branch)
        assertNull(FfmpegBuilds.parse("ffmpeg-n9.0.2-39-g7adfbe40dc-win64-lgpl-9.0.zip", libs))
        assertNull(FfmpegBuilds.parse("ffmpeg-n9.0.2-39-g7adfbe40dc-win64-gpl-shared-9.0.zip", cli))
        assertNull(FfmpegBuilds.parse("ffmpeg-n9.0.2-39-g7adfbe40dc-winarm64-gpl-9.0.zip", cli))
        assertNull(FfmpegBuilds.parse("checksums.sha256", cli))
    }

    @Test
    fun `a newer minor of the same major is taken`() {
        val names = listOf("ffmpeg-n9.1-latest-win64-lgpl-shared-9.1.zip", "ffmpeg-n10.0-latest-win64-lgpl-shared-10.0.zip")
        assertEquals("9.1", FfmpegBuilds.pickWanted(names, libs)?.branch)
    }

    @Test
    fun `once 9 is gone the command-line build takes the nearest newer branch, then master, then 8`() {
        val later = listOf(
            "ffmpeg-master-latest-win64-gpl.zip",
            "ffmpeg-n11.0-latest-win64-gpl-11.0.zip", "ffmpeg-n10.1-latest-win64-gpl-10.1.zip",
            "ffmpeg-n10.0-latest-win64-gpl-10.0.zip",
        )
        assertNull(FfmpegBuilds.pickWanted(later, cli))
        assertEquals("10.1", FfmpegBuilds.pickOther(later, cli)?.branch)
        assertEquals("master", FfmpegBuilds.pickOther(listOf("ffmpeg-master-latest-win64-gpl.zip"), cli)?.branch)
        val older = listOf("ffmpeg-n7.1-latest-win64-gpl-7.1.zip", "ffmpeg-n8.1-latest-win64-gpl-8.1.zip")
        assertEquals("8.1", FfmpegBuilds.pickOther(older, cli)?.branch)
        assertNull(FfmpegBuilds.pickOther(listOf("ffmpeg-n7.1-latest-win64-gpl-7.1.zip"), cli))
    }

    @Test
    fun `the wanted branch from a later release beats another branch from latest`() {
        val latest = sums("ffmpeg-n10.0-latest-win64-gpl-10.0.zip", "ffmpeg-master-latest-win64-gpl.zip")
        val pinned = sums("ffmpeg-n9.0.2-39-g7adfbe40dc-win64-gpl-9.0.zip")
        val read = mutableListOf<Release>()
        val found = FfmpegBuilds.locate(FfmpegBuilds.RELEASES, cli) { r ->
            read += r
            when (r) {
                FfmpegBuilds.LATEST -> latest
                FfmpegBuilds.PINNED -> pinned
                else -> null
            }
        }!!
        assertEquals(FfmpegBuilds.PINNED, found.release)
        assertEquals("9.0", found.build.branch)
        assertEquals(
            "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-10-31-12-59/" +
                "ffmpeg-n9.0.2-39-g7adfbe40dc-win64-gpl-9.0.zip",
            found.url,
        )
        assertEquals(listOf(FfmpegBuilds.LATEST, FfmpegBuilds.PINNED), read)
    }

    @Test
    fun `with no 9 anywhere the command line falls back to latest's next branch, the libraries to nothing`() {
        val latest = sums("ffmpeg-n10.0-latest-win64-gpl-10.0.zip", "ffmpeg-n10.0-latest-win64-lgpl-shared-10.0.zip")
        val only: (Release) -> Map<String, String>? = { r -> if (r == FfmpegBuilds.LATEST) latest else null }
        assertEquals("10.0", FfmpegBuilds.locate(FfmpegBuilds.RELEASES, cli, only)?.build?.branch)
        assertNull(FfmpegBuilds.locate(FfmpegBuilds.RELEASES, libs, only))
    }

    @Test
    fun `the month-end pin and the mirror come after latest`() {
        assertEquals(listOf(FfmpegBuilds.LATEST, FfmpegBuilds.PINNED, FfmpegBuilds.MIRROR), FfmpegBuilds.RELEASES.take(3))
    }
}
