package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.QueueAction
import com.dreamdisplays.core.protocol.common.packets.QueueActionType
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import com.dreamdisplays.core.protocol.common.packets.QueuePreload
import com.dreamdisplays.core.protocol.common.packets.QueuePreloadState
import com.dreamdisplays.core.protocol.common.packets.QueueState
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.shared.capability.ServerFeature
import net.minecraft.client.Minecraft
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Client view of every loaded display's "Up next" queue, as last sent by the server ([QueueState]), plus the queue
 * edits this client sends. The server decides everything; this only mirrors and asks.
 */
object DisplayQueues {
    private val states = ConcurrentHashMap<UUID, QueueState>()

    /** True when the connected server runs queues (it advertised [ServerFeature.QUEUE]). */
    val supported: Boolean get() = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.QUEUE)

    /** Applies a server queue snapshot. */
    fun apply(packet: QueueState) {
        states[packet.id] = packet
    }

    /** The last known queue of [displayId], or null when the server sent none. */
    fun of(displayId: UUID): QueueState? = states[displayId]

    /** The queued entries of [displayId], in play order. */
    fun items(displayId: UUID): List<QueueItemWire> = states[displayId]?.items.orEmpty()

    /** The entry that plays next on [displayId], or null. */
    fun next(displayId: UUID): QueueItemWire? = items(displayId).firstOrNull()

    /** How many entries the local player has waiting on [displayId]. */
    fun ownCount(displayId: UUID): Int {
        val me = Minecraft.getInstance().player?.uuid ?: return 0
        return items(displayId).count { it.addedBy == me }
    }

    /** True when the local player added [item]. */
    fun isMine(item: QueueItemWire): Boolean = Minecraft.getInstance().player?.uuid == item.addedBy

    /** Drops [displayId]'s queue (display unloaded); the server re-sends it with the next sync. */
    fun forget(displayId: UUID) {
        states.remove(displayId)
    }

    /** Forgets everything (disconnect). */
    fun reset() {
        states.clear()
    }

    /** Asks the server to queue [result] (a suggestion card or a resolved pasted link) on [displayId]. */
    fun add(displayId: UUID, result: MediaSearchResult, lang: String) {
        add(
            displayId, result.getWatchUrl(), lang, result.title,
            (result.durationSec ?: 0L).coerceAtLeast(0L) * 1_000L,
            result.thumbnailUrlOverride ?: result.getThumbnailUrl().orEmpty(),
        )
    }

    /** Asks the server to queue [url] on [displayId] with the metadata this client resolved. */
    fun add(displayId: UUID, url: String, lang: String, title: String, durationMs: Long, thumbnailUrl: String) {
        send(
            QueueAction(
                displayId, QueueActionType.ADD.wire, url = url, lang = lang, title = title.take(200),
                durationMs = durationMs, thumbnailUrl = thumbnailUrl.take(512),
            ),
        )
    }

    fun remove(displayId: UUID, itemId: String) = send(QueueAction(displayId, QueueActionType.REMOVE.wire, itemId = itemId))

    fun move(displayId: UUID, itemId: String, toIndex: Int) =
        send(QueueAction(displayId, QueueActionType.MOVE.wire, itemId = itemId, toIndex = toIndex))

    fun skip(displayId: UUID) = send(QueueAction(displayId, QueueActionType.SKIP.wire))

    fun clear(displayId: UUID) = send(QueueAction(displayId, QueueActionType.CLEAR.wire))

    fun setLoop(displayId: UUID, enabled: Boolean) = send(QueueAction(displayId, QueueActionType.LOOP.wire, flag = enabled))

    /** Tells the server how far this client got preloading [itemId]. */
    fun reportPreload(displayId: UUID, itemId: String, state: QueuePreloadState) {
        if (!supported) return
        Initializer.sendPacket(QueuePreload(displayId, itemId, state.wire))
    }

    private fun send(action: QueueAction) {
        if (!supported) return
        Initializer.sendPacket(action)
    }
}
