package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.core.activity.ActivityRecords
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/**
 * JSON persistence of every display's Activity rows (`display-activity.json`, next to the queues), so the list
 * survives restarts and plugin reloads. Modeled on [DisplayQueueStore]; the record format is [ActivityRecords]. An
 * unreadable file is only logged: the rows are a convenience, never worth refusing to start over.
 */
object ActivityStore {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "display-activity.json"
    private val jsonFiles = JsonFileStore()
    private val listSerializer = ListSerializer(ActivityRecords.Display.serializer())

    /** Every stored display's rows (empty when there is no file or it cannot be read). */
    fun load(): List<ActivityRecords.Display> =
        jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), listSerializer, SCHEMA_VERSION, logger).orEmpty()

    /** Overwrites the store with [records]. */
    fun save(records: List<ActivityRecords.Display>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, records, SCHEMA_VERSION, logger)
    }
}
