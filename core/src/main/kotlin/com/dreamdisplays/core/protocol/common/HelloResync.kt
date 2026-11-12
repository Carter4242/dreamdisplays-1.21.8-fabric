package com.dreamdisplays.core.protocol.common

import java.util.*

/**
 * Server side: keeps what each connected client was told it may do (its `ServerHello`: premium, admin, display cap, the
 * features it may use such as screen sharing) in step with its permissions while it stays online. A client learns
 * these only from a hello, so without this a permission granted or revoked after login reached it at its next login or
 * config reload.
 *
 * Two triggers, both ending in [changed] (a hello is sent again only when it differs from the last one sent):
 * - [request]: a permission plugin recalculated one player's permissions. One edit fires several of those (and a group
 *   edit one per member), so the player is rechecked [debounceMs] after the last one ([takeDue]).
 * - [sweepDue]: every [sweepEveryMs] everyone who got a hello is rechecked, for changes no event announces (op status,
 *   other permission plugins, permissions that depend on the world, features that came or went).
 *
 * Thread-safe: requests may come from a permission plugin's own threads.
 *
 * @param S the hello as it would be sent now; compared with [equals].
 */
class HelloResync<S : Any>(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val sweepEveryMs: Long = SWEEP_EVERY_MS,
) {
    /** The last hello each player got. */
    private val sent = HashMap<UUID, S>()

    /** Players to recheck, with when (epoch ms). */
    private val dueAt = HashMap<UUID, Long>()

    /** When the next sweep is due (epoch ms); [Long.MIN_VALUE] until the first [sweepDue] arms it. */
    private var nextSweepMs = Long.MIN_VALUE

    /** [id] was sent [hello] (its handshake, a config reload). */
    @Synchronized
    fun onSent(id: UUID, hello: S) {
        sent[id] = hello
    }

    /** [id]'s permissions were recalculated at [nowMs]: recheck them [debounceMs] after the last such call. */
    @Synchronized
    fun request(id: UUID, nowMs: Long) {
        dueAt[id] = nowMs + debounceMs
    }

    /** The players whose recheck is due at [nowMs]; each is returned once per [request]. */
    @Synchronized
    fun takeDue(nowMs: Long): List<UUID> {
        if (dueAt.isEmpty()) return emptyList()
        val due = dueAt.entries.filter { it.value <= nowMs }.map { it.key }
        due.forEach(dueAt::remove)
        return due
    }

    /** True when everyone is to be rechecked at [nowMs]: every [sweepEveryMs], counted from the first call. */
    @Synchronized
    fun sweepDue(nowMs: Long): Boolean {
        if (nextSweepMs == Long.MIN_VALUE) {
            nextSweepMs = nowMs + sweepEveryMs
            return false
        }
        if (nowMs < nextSweepMs) return false
        nextSweepMs = nowMs + sweepEveryMs
        return true
    }

    /** Everyone who got a hello and has not left. */
    @Synchronized
    fun known(): Set<UUID> = HashSet(sent.keys)

    /**
     * [current] is the hello [id] would get now. True when it differs from the last one they got: send it (it is
     * recorded as sent). False when it is the same, or when [id] never got one (their handshake sends a fresh one).
     */
    @Synchronized
    fun changed(id: UUID, current: S): Boolean {
        val last = sent[id] ?: return false
        if (last == current) return false
        sent[id] = current
        return true
    }

    /** [id] left. */
    @Synchronized
    fun forget(id: UUID) {
        sent.remove(id)
        dueAt.remove(id)
    }

    /** Forgets everyone (plugin disable, server stop). */
    @Synchronized
    fun clear() {
        sent.clear()
        dueAt.clear()
        nextSweepMs = Long.MIN_VALUE
    }

    companion object {
        /** A permission edit fires several recalculations within a few milliseconds: wait for the last one. */
        const val DEBOUNCE_MS: Long = 250L

        /** How often everyone is rechecked: a few permission checks per online player. */
        const val SWEEP_EVERY_MS: Long = 5_000L
    }
}
