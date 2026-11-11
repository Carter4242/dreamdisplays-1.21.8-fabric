package com.dreamdisplays.util.json

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileStoreTest {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())

    @Test
    fun writesAndReadsVersionedEnvelope() {
        val dir = Files.createTempDirectory("dreamdisplays-json-store").toFile()
        try {
            val store = JsonFileStore(dir)
            val file = store.file("store.json")

            store.writeVersioned(file, mapSerializer, mapOf("display" to 7), schemaVersion = 2, logger)

            val json = file.readText()
            assertTrue(json.contains("\"schemaVersion\": 2"))
            assertTrue(json.contains("\"data\""))
            assertEquals(
                mapOf("display" to 7),
                store.readVersioned(file, mapSerializer, schemaVersion = 2, logger),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readsLegacyPayloadWithoutEnvelope() {
        val dir = Files.createTempDirectory("dreamdisplays-json-store").toFile()
        try {
            val store = JsonFileStore(dir)
            val file = store.file("store.json")
            file.writeText("""{"data":7}""")

            assertEquals(
                mapOf("data" to 7),
                store.readVersioned(file, mapSerializer, schemaVersion = 1, logger),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsNewerSchemaVersion() {
        val dir = Files.createTempDirectory("dreamdisplays-json-store").toFile()
        try {
            val store = JsonFileStore(dir)
            val file = store.file("store.json")
            file.writeText("""{"schemaVersion":99,"data":{"display":7}}""")

            assertNull(store.readVersioned(file, mapSerializer, schemaVersion = 1, logger))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTargetBrieflyHeldOpenIsReplacedOnRetry() {
        val store = JsonFileStore(Files.createTempDirectory("dreamdisplays-json-retry").toFile())
        val tmp = File(store.dir, "a.json.tmp").apply { writeText("new") }
        val target = File(store.dir, "a.json").apply { writeText("old") }
        var busy = 2
        val pauses = mutableListOf<Long>()
        store.replace(tmp, target, move = { from, to, _ ->
            if (busy-- > 0) throw AccessDeniedException(to.path)
            from.renameTo(to.also { it.delete() })
        }, pause = { pauses += it })
        assertEquals("new", target.readText())
        assertEquals(listOf(25L, 50L), pauses)
    }

    @Test
    fun aTargetThatStaysLockedOrAMissingFileStillFails() {
        val store = JsonFileStore(Files.createTempDirectory("dreamdisplays-json-retry").toFile())
        val tmp = File(store.dir, "a.json.tmp")
        val target = File(store.dir, "a.json")
        var tries = 0
        assertFailsWith<AccessDeniedException> {
            store.replace(tmp, target, move = { _, to, _ -> tries++; throw AccessDeniedException(to.path) }, pause = {})
        }
        assertEquals(4, tries)
        tries = 0
        assertFailsWith<NoSuchFileException> {
            store.replace(tmp, target, move = { from, _, _ -> tries++; throw NoSuchFileException(from.path) }, pause = {})
        }
        assertEquals(1, tries)
    }
}
