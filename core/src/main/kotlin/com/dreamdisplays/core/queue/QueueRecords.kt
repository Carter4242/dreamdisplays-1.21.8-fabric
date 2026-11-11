package com.dreamdisplays.core.queue

import kotlinx.serialization.Serializable
import java.util.*

/** The stored (JSON) form of every display's queue; UUIDs are strings so the file stays readable. */
object QueueRecords {
    /** One stored queue entry. */
    @Serializable
    data class Item(
        val itemId: String,
        val url: String,
        val lang: String = "",
        val title: String = "",
        val durationMs: Long = 0,
        val thumbnailUrl: String = "",
        val addedBy: String,
        val addedByName: String = "",
        val addedAtMs: Long = 0,
    )

    /** One display's stored queue. */
    @Serializable
    data class Display(
        val displayId: String,
        val loop: Boolean = false,
        val current: Item? = null,
        val items: List<Item> = emptyList(),
    )

    /** Records for [queues]; queues with nothing to remember (empty, not looping) are left out. */
    fun toRecords(queues: Map<UUID, DisplayQueue>): List<Display> = queues.mapNotNull { (displayId, queue) ->
        if (queue.isEmpty() && !queue.loop) return@mapNotNull null
        Display(displayId.toString(), queue.loop, queue.current?.toRecord(), queue.items.map { it.toRecord() })
    }

    /** Queues from [records]; malformed ids and entries are skipped, never fatal. */
    fun fromRecords(records: List<Display>): Map<UUID, DisplayQueue> {
        val out = LinkedHashMap<UUID, DisplayQueue>()
        for (record in records) {
            val displayId = runCatching { UUID.fromString(record.displayId) }.getOrNull() ?: continue
            out[displayId] = DisplayQueue(record.items.mapNotNull { it.toItem() }, record.loop, record.current?.toItem())
        }
        return out
    }

    private fun Item.toItem(): QueueItem? {
        val adder = runCatching { UUID.fromString(addedBy) }.getOrNull() ?: return null
        if (itemId.isBlank() || url.isBlank()) return null
        return QueueItem(itemId, url, lang, title, durationMs, thumbnailUrl, adder, addedByName, addedAtMs)
    }

    private fun QueueItem.toRecord() =
        Item(itemId, url, lang, title, durationMs, thumbnailUrl, addedBy.toString(), addedByName, addedAtMs)
}
