package com.dreamdisplays.core.protocol.common

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The commit graph a server plugin was built from (`dreamdisplays-commits.txt`: `<commit> <parent>...` per line,
 * 8-digit ids, `#` comments), for placing a client's build id relative to the current release.
 */
class CommitHistory private constructor(private val parents: Map<String, List<String>>) {
    /** Number of commits known. */
    val size: Int get() = parents.size

    /** True when no history was bundled (built without git). */
    val isEmpty: Boolean get() = parents.isEmpty()

    private val ancestorCache = ConcurrentHashMap<String, Set<String>>()

    /** The known commit [id] stands for (8 digits; a shorter prefix when it names exactly one commit), or null. */
    fun resolve(id: String?): String? {
        val key = ClientBuild.commitOf(id) ?: return null
        if (key in parents) return key
        if (key.length >= 8) return null
        return parents.keys.filter { it.startsWith(key) }.singleOrNull()
    }

    /** True when [commit] is a strict ancestor of [descendant] (both must be known; a commit is not its own ancestor). */
    fun isStrictAncestor(commit: String?, descendant: String?): Boolean {
        val c = resolve(commit) ?: return false
        val d = resolve(descendant) ?: return false
        return c != d && c in ancestorsOf(d)
    }

    private fun ancestorsOf(commit: String): Set<String> = ancestorCache.getOrPut(commit) {
        val seen = HashSet<String>()
        val pending = ArrayDeque(parents[commit].orEmpty())
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.add(next)) pending.addAll(parents[next].orEmpty())
        }
        seen
    }

    companion object {
        val EMPTY = CommitHistory(emptyMap())

        fun parse(text: String): CommitHistory {
            val map = HashMap<String, List<String>>()
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val ids = line.split(' ').mapNotNull { ClientBuild.commitOf(it) }
                if (ids.isNotEmpty()) map[ids[0]] = ids.drop(1)
            }
            return CommitHistory(map)
        }
    }
}

/**
 * A mod version as `MAJOR.MINOR[.PATCH][-PRERELEASE][+BUILD]`, ordered as semantic versions (a pre-release sorts below
 * its release, build metadata is ignored).
 */
data class ModVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: List<String>) : Comparable<ModVersion> {
    override fun compareTo(other: ModVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) return other.preRelease.size.compareTo(0) - preRelease.size.compareTo(0)
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else preRelease.joinToString(".", "-")

    companion object {
        private val PATTERN = Regex("""v?(\d{1,6})\.(\d{1,6})(?:\.(\d{1,6}))?(?:-([0-9A-Za-z.-]{1,32}))?(?:\+[0-9A-Za-z.-]*)?""")

        /** The version [raw] names, or null when it isn't one. */
        fun parse(raw: String?): ModVersion? {
            val m = PATTERN.matchEntire(raw?.trim() ?: return null) ?: return null
            return ModVersion(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(),
                m.groupValues[4].split('.').filter { it.isNotEmpty() },
            )
        }
    }
}

/**
 * Whether a client should be told to update, from its hello's mod version and build id.
 *
 * - The mod versions differ (both parse): an older one needs the update, a newer one never does.
 * - Same version (every build before 2.0.0 reported 1.10.9): by commit. No build id at all (jars before ids existed)
 *   is older; a commit that is an ancestor of the current release is older (`-dev` / `-dirty` suffixes ignored);
 *   the release itself, newer commits and commits off the release's history (branch dev builds) are not.
 * - Urgent (builds known to hurt other players' playback): the no-build-id jars when [Rules.urgentUnknown], any commit
 *   in [Rules.urgentBuilds] (whatever its version), and every commit strictly older than [Rules.urgentBefore].
 */
object ClientUpdateCheck {
    enum class Verdict { CURRENT, OLDER, URGENT }

    data class Rules(
        /** The current release's mod version; null = compare by commit only. */
        val latestVersion: ModVersion?,
        /** The current release's build id (commit); empty = unknown, so commits are never called older. */
        val latestBuild: String,
        val history: CommitHistory,
        val urgentBuilds: Collection<String> = emptyList(),
        /** Builds strictly older than this commit are urgent; empty = none. */
        val urgentBefore: String = "",
        val urgentUnknown: Boolean = true,
    )

    fun classify(modVersion: String?, buildCommit: String?, rules: Rules): Verdict {
        val build = ClientBuild.of(buildCommit)
        val commit = ClientBuild.commitOf(build)
        val noBuildId = build == null
        val explicitUrgent = commit != null && rules.urgentBuilds.any { ClientBuild.commitOf(it)?.let(commit::startsWith) == true }
        if (explicitUrgent) return Verdict.URGENT

        val client = ModVersion.parse(modVersion)
        val latest = rules.latestVersion
        val versionOrder = if (client != null && latest != null) client.compareTo(latest) else 0
        if (versionOrder > 0) return Verdict.CURRENT

        val older = when {
            versionOrder < 0 -> true
            noBuildId -> true
            else -> rules.history.isStrictAncestor(commit, rules.latestBuild)
        }
        if (!older) return Verdict.CURRENT
        val urgent = (noBuildId && rules.urgentUnknown) ||
                (rules.urgentBefore.isNotEmpty() && rules.history.isStrictAncestor(commit, rules.urgentBefore))
        return if (urgent) Verdict.URGENT else Verdict.OLDER
    }
}

/**
 * Who has had their update notice this login. A notice is due once per login: [begin] hands out a token for a player's
 * first hello of a login (null for later ones, and for players already online when the plugin started, whose hello
 * answers a reload's greeting); a delayed notice only goes out while [isCurrent] still holds its token.
 */
class UpdateNoticeSessions {
    private val sessions = ConcurrentHashMap<UUID, Any>()
    private val onlineAtStart: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Players online when the plugin started: they logged in under an earlier instance, which handled their login. */
    fun pluginStarted(online: Collection<UUID>) {
        onlineAtStart.clear()
        onlineAtStart.addAll(online)
    }

    /** A token for [player]'s notice, or null when this login already had one or began before the plugin started. */
    fun begin(player: UUID): Any? {
        if (player in onlineAtStart) return null
        val token = Any()
        return if (sessions.putIfAbsent(player, token) == null) token else null
    }

    fun isCurrent(player: UUID, token: Any): Boolean = sessions[player] === token

    /** The login ended: the next one gets its own notice. */
    fun onQuit(player: UUID) {
        sessions.remove(player)
        onlineAtStart.remove(player)
    }
}
