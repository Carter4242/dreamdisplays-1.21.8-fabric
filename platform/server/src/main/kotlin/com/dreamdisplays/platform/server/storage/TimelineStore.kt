package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.core.sync.TimelineRecords
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/**
 * JSON hand-over of the display timelines from a disabled plugin instance to the next one (`display-timelines.json`,
 * next to the queues). Written on disable, read and deleted on enable: after a crash, with nothing written, every
 * display starts afresh rather than from a stale record.
 */
object TimelineStore {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "display-timelines.json"
    private val jsonFiles = JsonFileStore()
    private val listSerializer = ListSerializer(TimelineRecords.Saved.serializer())

    /** The saved timelines (empty when none), deleting the file so they are used once. */
    fun take(): List<TimelineRecords.Saved> {
        val file = jsonFiles.file(FILE_NAME)
        val records = jsonFiles.readVersioned(file, listSerializer, SCHEMA_VERSION, logger).orEmpty()
        if (file.isFile && !file.delete()) logger.warn("Could not delete {}.", file.name)
        return records
    }

    /** Overwrites the store with [records]. */
    fun save(records: List<TimelineRecords.Saved>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, records, SCHEMA_VERSION, logger)
    }
}
