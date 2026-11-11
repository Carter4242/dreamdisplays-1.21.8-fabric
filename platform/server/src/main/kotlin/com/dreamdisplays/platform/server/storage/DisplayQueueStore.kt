package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.core.queue.DisplayQueue
import com.dreamdisplays.core.queue.QueueRecords
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /**
     * Loads every stored queue, or an empty map; unreadable entries are skipped. A file that cannot be read at all is
     * moved aside (`.unreadable`) first, so the next save does not overwrite the only copy.
     */
    fun load(): Map<UUID, DisplayQueue> {
        val file = jsonFiles.file(FILE_NAME)
        val records = jsonFiles.readVersioned(file, listSerializer, SCHEMA_VERSION, logger)
        if (records == null) {
            if (file.isFile) setAside(file)
            return emptyMap()
        }
        return QueueRecords.fromRecords(records)
    }

    private fun setAside(file: File) {
        val aside = File(file.parentFile, file.name + ".unreadable")
        runCatching { Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onSuccess { logger.warn("Moved the unreadable {} to {}; queues start empty.", file.name, aside.name) }
            .onFailure { logger.error("Could not move the unreadable {} aside.", file.name, it) }
    }

    /** Overwrites the store with [queues]. */
    fun save(queues: Map<UUID, DisplayQueue>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, QueueRecords.toRecords(queues), SCHEMA_VERSION, logger)
    }
}
