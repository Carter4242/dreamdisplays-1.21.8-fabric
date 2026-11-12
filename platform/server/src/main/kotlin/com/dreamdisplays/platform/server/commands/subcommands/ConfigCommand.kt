package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.core.config.TomlLineEditor
import com.dreamdisplays.core.display.policy.PolicyPaths
import com.dreamdisplays.core.protocol.common.ClientBuild
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.SettingsSection
import com.dreamdisplays.platform.server.utils.SecretLogFilter
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `/display config [<key> [<value>|reset]]`: reads or sets the server-wide display defaults in `config.toml`
 * (the `[display]` hold / mode / auto-pause keys, `[clients] latest_build`, the `[karaoke]` keys, the `[live]` relay URLs and
 * every `[policy.*]` default), then reloads the config the way `/display reload` does. The relay publish URL carries
 * credentials: it is only ever shown, replied and logged with them redacted.
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
        /** Holds credentials: shown, echoed and logged only through [redact]. */
        val secret: Boolean = false,
        val redact: (String) -> String = LiveStreamUrls::redactCredentials,
        val current: (SettingsSection) -> Any?,
    ) {
        val table: String = name.substringBeforeLast('.')
        val key: String = name.substringAfterLast('.')
    }

    private val keys: List<Key> = listOf(
        Key("display.resume_hold_seconds", PolicyPaths.Kind.Whole(0, 60)) { it.display.resume_hold_seconds },
        Key("display.seek_hold_seconds", PolicyPaths.Kind.Whole(0, 60)) { it.display.seek_hold_seconds },
        Key("display.speed_change_lead_ms", PolicyPaths.Kind.Whole(0, 2_000)) { it.display.speed_change_lead_ms },
        Key("display.default_mode", PolicyPaths.Kind.Choice(listOf("local", "synced"))) {
            it.defaultMode.name.lowercase()
        },
        Key("display.auto_pause_when_empty_seconds", PolicyPaths.Kind.Whole(0, 86_400)) {
            it.display.auto_pause_when_empty_seconds
        },
        Key("clients.latest_build", PolicyPaths.Kind.Text(ClientBuild.LATEST_PATTERN, "<commit, e.g. 1a2b3c4d>")) {
            it.clients.latest_build.ifEmpty { null }
        },
        Key("clients.error_reports", PolicyPaths.Kind.Bool) { it.clients.error_reports },
        Key("clients.command_log", PolicyPaths.Kind.Bool) { it.clients.command_log },
        Key("karaoke.default_lead_ms", PolicyPaths.Kind.Whole(0, 3_000)) { it.karaoke.default_lead_ms },
        Key("karaoke.lead_margin_ms", PolicyPaths.Kind.Whole(0, 1_000)) { it.karaoke.lead_margin_ms },
        Key("karaoke.min_lead_ms", PolicyPaths.Kind.Whole(0, 3_000)) { it.karaoke.min_lead_ms },
        Key("karaoke.max_lead_ms", PolicyPaths.Kind.Whole(0, 3_000)) { it.karaoke.max_lead_ms },
        Key("karaoke.singer_output_capture_ms", PolicyPaths.Kind.Whole(0, 1_000)) { it.karaoke.singer_output_capture_ms },
        Key("karaoke.listener_buffer_output_ms", PolicyPaths.Kind.Whole(0, 1_000)) { it.karaoke.listener_buffer_output_ms },
        Key("karaoke.absent_singer", PolicyPaths.Kind.Choice(listOf("skip", "play"))) { it.karaoke.absent_singer },
        Key("karaoke.track_volume", PolicyPaths.Kind.Decimal(0.0, 1.0)) { it.karaoke.track_volume },
        Key("karaoke.singer_volume", PolicyPaths.Kind.Decimal(0.0, 3.0)) { it.karaoke.singer_volume },
        Key("karaoke.voice_source", PolicyPaths.Kind.Choice(listOf("singer", "display"))) { it.karaoke.voice_source },
        Key("karaoke.co_singer_volume", PolicyPaths.Kind.Decimal(0.0, 1.0)) { it.karaoke.co_singer_volume },
        Key("karaoke.co_singer_idle_volume", PolicyPaths.Kind.Decimal(0.0, 1.0)) { it.karaoke.co_singer_idle_volume },
        Key("karaoke.co_singer_idle_after_ms", PolicyPaths.Kind.Whole(0, 60_000)) { it.karaoke.co_singer_idle_after_ms },
        Key("karaoke.co_singer_fade_down_ms", PolicyPaths.Kind.Whole(0, 5_000)) { it.karaoke.co_singer_fade_down_ms },
        Key("karaoke.co_singer_fade_up_ms", PolicyPaths.Kind.Whole(0, 5_000)) { it.karaoke.co_singer_fade_up_ms },
        Key("karaoke.singer_track_volume", PolicyPaths.Kind.Decimal(0.0, 2.0)) { it.karaoke.singer_track_volume },
        Key("karaoke.debug", PolicyPaths.Kind.Bool) { it.karaoke.debug },
        Key("live.relay_publish_url", PUBLISH_URL, secret = true) {
            it.live.relay_publish_url.ifEmpty { null }?.let(LiveStreamUrls::redactCredentials)
        },
        Key("live.relay_play_url", RELAY_URL) { it.live.relay_play_url.ifEmpty { null } },
        Key("live.share_encoder", PolicyPaths.Kind.Choice(listOf("auto", "software"))) { it.live.share_encoder },
        Key("live.share_max_fps", PolicyPaths.Kind.Whole(0, 60)) { it.live.share_max_fps },
        Key("live.share_max_height", PolicyPaths.Kind.Whole(0, 2160)) { it.live.share_max_height },
        Key("live.share_bitrate_kbps", PolicyPaths.Kind.Whole(0, 20_000)) { it.live.share_bitrate_kbps },
        Key("live.share_sound", PolicyPaths.Kind.Bool) { it.live.share_sound },
        Key("live.relay_control_url", PolicyPaths.Kind.Text(Regex("""(?i)https://[^\s"]+"""), "<https URL of the relay lease service>")) {
            it.live.relay_control_url.ifEmpty { null }
        },
        Key(
            "live.relay_control_token", PolicyPaths.Kind.Text(Regex("""[^\s"]{16,}"""), "<token, 16+ characters>"),
            secret = true, redact = { SecretLogFilter.REDACTED },
        ) { it.live.relay_control_token.ifEmpty { null }?.let { SecretLogFilter.REDACTED } },
    ) +PolicyPaths.all.map { path -> Key("policy.${path.name}", path.kind) { path.get(it.policy) } }

    private val byName = keys.associateBy { it.name }

    /**
     * A publish URL template: a scheme the client can publish to (no RTSP: the client has no RTSP muxer) and an `{id}`.
     * The full check (a valid URL once filled in, `{key}` when a lease service is set) runs when the config loads.
     */
    private val PUBLISH_URL: PolicyPaths.Kind
        get() = PolicyPaths.Kind.Text(
            Regex("""(?i)(?:srt|rtmps?|rtsp)://[^\s"]*\{id}[^\s"]*"""),
            "<srt|rtmp|rtmps|rtsp URL containing {id}>",
        )

    /** A relay URL template: a live scheme and an `{id}` somewhere (path, `streamid`, ...); any query is kept. */
    private val RELAY_URL: PolicyPaths.Kind
        get() = PolicyPaths.Kind.Text(
            Regex("""(?i)(?:srt|rtmps?|rtsp)://[^\s"]*\{id}[^\s"]*"""),
            "<srt|rtmp|rtmps|rtsp URL containing {id}>",
        )

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
        // A player's command line is logged (server log filters aside, other plugins store it): secrets come from the console
        if (entry.secret && sender is Player) {
            ServerMessage.CONFIG_SECRET_FROM_CONSOLE.sendTo(sender, entry.name)
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
        now.live.relayProblem?.let {
            ServerMessage.CONFIG_RELAY_PROBLEM.sendTo(sender, com.dreamdisplays.api.media.source.LiveStreamUrls.redactCredentials(it))
        }
    }

    /** Writes [value] (null = comment the key out) into `config.toml` and reloads; returns an error text or null. */
    private fun write(entry: Key, value: Any?): String? = runCatching {
        val file = PaperServer.config.file.toPath()
        val text = Files.readString(file)
        val updated = if (value == null) TomlLineEditor.unset(text, entry.table, entry.key)
        else TomlLineEditor.set(text, entry.table, entry.key, TomlLineEditor.encode(value))
        val check = Toml.parse(updated)
        if (check.hasErrors()) return LiveStreamUrls.redactCredentials("${check.errors().first().message}")
        if (value != null && check.get("${entry.table}.${entry.key}") == null) return "the key did not land in [${entry.table}]"
        if (updated != text) {
            val temp = file.resolveSibling("config.toml.tmp")
            Files.writeString(temp, updated)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        PaperServer.config.reload()
        val shown = if (entry.secret && value is String) entry.redact(value) else value
        logger.info("config.toml: {} {}", entry.name, if (value == null) "reset" else "= $shown")
        null
    }.getOrElse { e ->
        logger.warn("Could not update config.toml for {}: {}", entry.name, LiveStreamUrls.redactCredentials(e.toString()))
        LiveStreamUrls.redactCredentials(e.message ?: e.javaClass.simpleName)
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
