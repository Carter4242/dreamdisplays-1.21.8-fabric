package com.dreamdisplays.core.display.policy

/**
 * One player's own client policy: overrides of the server-wide fields ([DisplayPolicy.serverWide]: the `client` and
 * `debug` sections) that only that player's client gets, on top of the server-wide `[policy.*]` defaults. Lets a
 * server move a single player off a misbehaving client path (decoder, drawing, audio backend, resolvers, ...) without
 * changing it for everyone. The client needs nothing new: it gets its usual server-wide policy, with these merged in.
 */
object PlayerPolicies {
    /** The keys a player override may set: the server-wide-only ones ([DisplayPolicy.GLOBAL_ONLY]). */
    val keys: List<PolicyPaths.Path> get() = PolicyPaths.all.filter { isPlayerKey(it) }

    /**
     * True when [path] may be set for one player. Not `client.enabled`, `client.display_distance_chunks` or
     * `client.audio_acoustics`: every display description carries its own copy of those, which would undo a player's.
     */
    fun isPlayerKey(path: PolicyPaths.Path): Boolean = path.serverWideOnly

    /**
     * [override] with [path] set from [raw] (null clears it), or null when [path] is no player key or [raw] does not
     * parse. An override that ends up setting nothing comes back as [DisplayPolicy.EMPTY].
     */
    fun withKey(override: DisplayPolicy?, path: PolicyPaths.Path, raw: String?): DisplayPolicy? {
        if (!isPlayerKey(path)) return null
        val updated = PolicyPaths.apply(override, path, raw) ?: return null
        return updated.serverWide().takeIf { !it.isEmpty } ?: DisplayPolicy.EMPTY
    }

    /** What a player's client is sent: [serverWide] with their [override] on top (server-wide fields only). */
    fun applyTo(serverWide: DisplayPolicy, override: DisplayPolicy?): DisplayPolicy {
        if (override == null || override.isEmpty) return serverWide
        return override.serverWide().mergedOver(serverWide).serverWide()
    }
}
