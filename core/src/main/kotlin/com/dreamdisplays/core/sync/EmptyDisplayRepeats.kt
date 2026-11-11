package com.dreamdisplays.core.sync

import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * How often an empty display ([EmptyDisplays.hasVideo] false) is described to a client older than
 * [EmptyDisplays.CLEAR_PROTOCOL]. Such a client takes an empty URL as a change every time it hears one (it saves its
 * settings file and asks for the display again), so the once-a-second refresh would keep it busy. It still has to hear
 * about the display, or it could neither see nor click one that was just created or cleared: it is described once,
 * again at once whenever the description changes, and unchanged at most every [repeatMs] (in case it lost the
 * display). Every other description goes out as usual.
 */
class EmptyDisplayRepeats(private val repeatMs: Long = REPEAT_MS) {
    private data class Key(val playerId: UUID, val displayId: UUID)

    private class Sent(val description: Int, val atMs: Long)

    private val sent = ConcurrentHashMap<Key, Sent>()

    /** True when [info] goes to [playerId] (a client on [protocolVersion]; 0 = unknown) at [nowMs]. */
    fun shouldSend(playerId: UUID, info: DisplayInfo, protocolVersion: Int, nowMs: Long): Boolean {
        val key = Key(playerId, info.id)
        if (EmptyDisplays.hasVideo(info.url) || !EmptyDisplays.dropBeforeClear(protocolVersion)) {
            if (sent.isNotEmpty()) sent.remove(key)
            return true
        }
        val description = describe(info)
        val last = sent[key]
        if (last != null && last.description == description && nowMs - last.atMs < repeatMs) return false
        sent[key] = Sent(description, nowMs)
        return true
    }

    /** [playerId]'s client was told to drop [displayId]: the next description of it goes out at once. */
    fun forget(playerId: UUID, displayId: UUID) {
        if (sent.isNotEmpty()) sent.remove(Key(playerId, displayId))
    }

    /** [playerId] left. */
    fun forgetPlayer(playerId: UUID) {
        if (sent.isNotEmpty()) sent.keys.removeIf { it.playerId == playerId }
    }

    companion object {
        /** An unchanged empty display is described again after this long at the earliest. */
        const val REPEAT_MS = 30_000L

        /** [info] as one number; the custom data by content (byte arrays have no value equality). */
        private fun describe(info: DisplayInfo): Int =
            31 * info.copy(customData = emptyMap()).hashCode() +
                info.customData.entries.sumOf { (key, value) -> key.hashCode() xor value.contentHashCode() }
    }
}
