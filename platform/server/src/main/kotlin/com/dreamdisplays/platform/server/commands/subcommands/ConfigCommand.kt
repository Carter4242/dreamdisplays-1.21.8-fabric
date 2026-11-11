package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.config.TomlLineEditor
import com.dreamdisplays.core.display.policy.PolicyPaths
import com.dreamdisplays.core.protocol.common.ClientBuild
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.SettingsSection
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.slf4j.LoggerFactory
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `/display config [<key> [<value>|reset]]`: reads or sets the server-wide display defaults in `config.toml`
 * (the `[display]` hold / mode / auto-pause keys, `[clients] latest_build` and every `[policy.*]` default), then
 * reloads the config the way `/display reload` does.
 *
 * The file is edited in place, one line at a time ([TomlLineEditor]), so comments and layout survive; the
 * result is parsed before it replaces the old file, and a reset comments the line out again so the built-in
 * default applies.
 */
@PaperOnly
object ConfigCommand {
    private val logger = LoggerFactory.getLogger(javaClass)

    private class Key(
        val name: String,
        val kind: PolicyPaths.Kind,
        val current: (SettingsSection) -> Any?,
    ) {
        val table: String = name.substringBeforeLast('.')
        val key: String = name.substringAfterLast('.')
    }

    private val keys: List<Key> = listOf(
        Key("display.resume_hold_seconds", PolicyPaths.Kind.Whole(0, 60)) { it.display.resume_hold_seconds },
        Key("display.seek_hold_seconds", PolicyPaths.Kind.Whole(0, 60)) { it.display.seek_hold_seconds },
        Key("display.default_mode", PolicyPaths.Kind.Choice(listOf("local", "synced"))) {
            it.defaultMode.name.lowercase()
        },
        Key("display.auto_pause_when_empty_seconds", PolicyPaths.Kind.Whole(0, 86_400)) {
            it.display.auto_pause_when_empty_seconds
        },
        Key("clients.latest_build", PolicyPaths.Kind.Text(ClientBuild.LATEST_PATTERN, "<commit, e.g. 1a2b3c4d>")) {
            it.clients.latest_build.ifEmpty { null }
        },
    ) +PolicyPaths.all.map { path -> Key("policy.${path.name}", path.kind) { path.get(it.policy) } }

    private val byName = keys.associateBy { it.name }

    /** Entry point; [key] and [value] are null when not typed. */
    fun execute(sender: CommandSender, key: String?, value: String?) {
        val settings = PaperServer.config.settings
        if (key == null) {
            ServerMessage.CONFIG_HEADER.sendTo(sender)
            keys.forEach { ServerMessage.CONFIG_VALUE.sendTo(sender, it.name, PolicyPaths.format(it.current(settings))) }
            return
        }
        val entry = byName[key.lowercase()] ?: run {
            ServerMessage.CONFIG_UNKNOWN_KEY.sendTo(sender, key)
            return
        }
        if (value == null) {
            ServerMessage.CONFIG_VALUE.sendTo(sender, entry.name, PolicyPaths.format(entry.current(settings)))
            return
        }
        val reset = value.equals(PolicyCommand.RESET, ignoreCase = true)
        val parsed = if (reset) null else entry.kind.parse(value.trim()) ?: run {
            ServerMessage.CONFIG_BAD_VALUE.sendTo(sender, entry.name, hint(entry.kind))
            return
        }
        val error = write(entry, parsed)
        if (error != null) {
            ServerMessage.CONFIG_WRITE_FAILED.sendTo(sender, error)
            return
        }
        val now = PaperServer.config.settings
        if (reset) ServerMessage.CONFIG_RESET.sendTo(sender, entry.name, PolicyPaths.format(entry.current(now)))
        else ServerMessage.CONFIG_SET.sendTo(sender, entry.name, PolicyPaths.format(entry.current(now)))
    }

    /** Writes [value] (null = comment the key out) into `config.toml` and reloads; returns an error text or null. */
    private fun write(entry: Key, value: Any?): String? = runCatching {
        val file = PaperServer.config.file.toPath()
        val text = Files.readString(file)
        val updated = if (value == null) TomlLineEditor.unset(text, entry.table, entry.key)
        else TomlLineEditor.set(text, entry.table, entry.key, TomlLineEditor.encode(value))
        val check = Toml.parse(updated)
        if (check.hasErrors()) return "${check.errors().first().message}"
        if (value != null && check.get("${entry.table}.${entry.key}") == null) return "the key did not land in [${entry.table}]"
        if (updated != text) {
            val temp = file.resolveSibling("config.toml.tmp")
            Files.writeString(temp, updated)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        PaperServer.config.reload()
        logger.info("config.toml: {} {}", entry.name, if (value == null) "reset" else "= $value")
        null
    }.getOrElse { e ->
        logger.warn("Could not update config.toml for {}.", entry.name, e)
        e.message ?: e.javaClass.simpleName
    }

    private fun hint(kind: PolicyPaths.Kind): String = PolicyPaths.Path("", "", kind) { null }.hint

    /** Suggestions for the key slot. */
    fun keySuggestions(): List<String> = keys.map { it.name }

    /** Suggestions for the value slot of [key]: its choices (or true / false) plus `reset`. */
    fun valueSuggestions(key: String): List<String> {
        val entry = byName[key.lowercase()] ?: return []
        return when (val kind = entry.kind) {
            PolicyPaths.Kind.Bool -> ["true", "false"]
            is PolicyPaths.Kind.Choice -> kind.values
            else -> []
        } + PolicyCommand.RESET
    }
}
