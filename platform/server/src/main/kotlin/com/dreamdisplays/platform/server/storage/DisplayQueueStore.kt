package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.core.queue.DisplayQueue
import com.dreamdisplays.core.queue.QueueRecords
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.util.*

/**
 * JSON persistence for every display's "Up next" queue (`display-queues.json`, next to the PiP pins), so queues
 * survive restarts and plugin reloads. Modeled on [PipPinStore]; the record format is [QueueRecords].
 */
object DisplayQueueStore {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "display-queues.json"
    private val jsonFiles = JsonFileStore()
    private val listSerializer = ListSerializer(QueueRecords.Display.serializer())

    /** Loads every stored queue, or an empty map; unreadable entries are skipped. */
    fun load(): Map<UUID, DisplayQueue> {
        val records = jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), listSerializer, SCHEMA_VERSION, logger)
            ?: return emptyMap()
        return QueueRecords.fromRecords(records)
    }

    /** Overwrites the store with [queues]. */
    fun save(queues: Map<UUID, DisplayQueue>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, QueueRecords.toRecords(queues), SCHEMA_VERSION, logger)
    }
}
