package com.dreamdisplays.media.runtime.download

import org.slf4j.LoggerFactory

/**
 * Where `FFmpeg` builds for Windows and Linux come from, and which file of a release to take.
 *
 * FFmpeg-Builds (formerly BtbN/FFmpeg-Builds) publishes a rolling `latest` release that carries master plus only the two
 * newest release branches: a newer minor replaces the older one (`n9.0-latest` is gone once 9.1 ships). Its dated
 * month-end `autobuild-*` releases stay up for about two years, with versioned file names. A release is read through
 * its `checksums.sha256`, which names every file it carries and what each must hash to; that is a plain download, so
 * there is no GitHub API rate limit in the way.
 *
 * Every source is a GitHub release of FFmpeg-Builds (never one a server names: a server could then run code on every
 * client). Releases are tried in [RELEASES] order, each for the wanted branch first ([Want.major]); only when none has it does
 * a caller that can run another branch ([Want.otherMajors]) take the nearest one.
 */
object FfmpegBuilds {
    private val logger = LoggerFactory.getLogger(javaClass)

    const val REPO = "FFmpeg-Builds/FFmpeg-Builds"
    private const val OLD_REPO = "BtbN/FFmpeg-Builds"

    /** A month-end build carrying release branch 9.0 for every platform; kept until about late 2028. */
    const val PINNED_TAG = "autobuild-2026-10-31-12-59"

    /** A GitHub release: `https://github.com/<repo>/releases/download/<tag>/<file>`. */
    data class Release(val repo: String, val tag: String) {
        fun url(file: String): String = "https://github.com/$repo/releases/download/$tag/$file"
        val sumsUrl: String get() = url("checksums.sha256")
        override fun toString(): String = "$repo@$tag"
    }

    val LATEST = Release(REPO, "latest")
    val PINNED = Release(REPO, PINNED_TAG)
    private val OLD_LATEST = Release(OLD_REPO, "latest")

    /** The order releases are tried in: newest builds first, then the fixed fallbacks. */
    val RELEASES: List<Release> = listOf(LATEST, PINNED, OLD_LATEST)

    /** `gpl` is the static command-line build; `lgpl-shared` the shared libraries the in-process decoder loads. */
    enum class Variant(val token: String) { GPL("gpl"), LGPL_SHARED("lgpl-shared") }

    /**
     * What a caller can use: release branch [major] (any minor: the library majors only change with `x.0`); with
     * [otherMajors] also the nearest newer branch, then master, then older branches down to [oldestMajor].
     */
    data class Want(
        val arch: String, val extension: String, val variant: Variant,
        val major: Int, val otherMajors: Boolean = false, val oldestMajor: Int = major,
    )

    /** One file of a release: its branch (`9.0`, or `master` with a null [major]). */
    data class Build(val name: String, val major: Int?, val minor: Int) {
        val branch: String get() = if (major == null) "master" else "$major.$minor"
    }

    /** A chosen file: where it is and what it must hash to. */
    data class Located(val release: Release, val build: Build, val sha256: String) {
        val url: String get() = release.url(build.name)
    }

    /** [name] as a [want] build, or null when it is another platform / variant / not a build. */
    internal fun parse(name: String, want: Want): Build? {
        val tail = "-${Regex.escape(want.arch)}-${Regex.escape(want.variant.token)}"
        val ext = Regex.escape(want.extension)
        Regex("""^ffmpeg-n\d+\.\d+[^-]*(?:-latest|-\d+-g[0-9a-f]+)?$tail-(\d+)\.(\d+)\.$ext$""").matchEntire(name)?.let { m ->
            return Build(name, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        if (Regex("""^ffmpeg-(?:master-latest|N-\d+-g[0-9a-f]+)$tail\.$ext$""").matches(name)) return Build(name, null, 0)
        return null
    }

    /** The build of [want.major] in [names] (newest minor), or null. */
    internal fun pickWanted(names: Collection<String>, want: Want): Build? =
        names.mapNotNull { parse(it, want) }.filter { it.major == want.major }.maxByOrNull { it.minor }

    /** The nearest other branch in [names]: the oldest newer major, then master, then the newest allowed older one. */
    internal fun pickOther(names: Collection<String>, want: Want): Build? {
        val builds = names.mapNotNull { parse(it, want) }
        builds.filter { it.major != null && it.major > want.major }
            .minWithOrNull(compareBy<Build> { it.major }.thenByDescending { it.minor })?.let { return it }
        builds.firstOrNull { it.major == null }?.let { return it }
        return builds.filter { it.major != null && it.major < want.major && it.major >= want.oldestMajor }
            .maxWithOrNull(compareBy<Build> { it.major }.thenBy { it.minor })
    }

    /**
     * Finds a build for [want] in [releases] (see the class comment for the order); null when none has one. A release
     * that can't be read (gone, offline, not created yet) is skipped.
     */
    fun locate(releases: List<Release>, want: Want, sums: (Release) -> Map<String, String>? = ::readSums): Located? {
        val read = LinkedHashMap<Release, Map<String, String>>()
        for (release in releases) {
            val list = sums(release) ?: continue
            read[release] = list
            pickWanted(list.keys, want)?.let { return Located(release, it, list.getValue(it.name)) }
        }
        if (!want.otherMajors) return null
        for ((release, list) in read) {
            pickOther(list.keys, want)?.let { return Located(release, it, list.getValue(it.name)) }
        }
        return null
    }

    private fun readSums(release: Release): Map<String, String>? =
        runCatching { SourceLoader.sha256Sums(release.sumsUrl) }
            .onFailure { e -> logger.info("No FFmpeg builds read from $release (${e.javaClass.simpleName}: ${e.message}).") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
}
