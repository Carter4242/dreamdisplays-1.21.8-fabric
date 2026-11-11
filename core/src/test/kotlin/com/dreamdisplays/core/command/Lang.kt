package com.dreamdisplays.core.command

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The server's bundled English messages and plugin descriptor, read from the repository. */
internal object Lang {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    val en: Map<String, String> by lazy {
        val text = File(repo, "platform/resources/src/main/resources/assets/dreamdisplays/lang/server/en.json").readText()
        (Json.parseToJsonElement(text).jsonObject as JsonObject)
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
    }

    val pluginYml: String by lazy {
        File(repo, "platform/server/src/main/resources/paper-plugin.yml").readText().replace("\r\n", "\n")
    }
}
